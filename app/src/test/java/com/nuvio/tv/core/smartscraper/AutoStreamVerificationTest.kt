package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.core.smartscraper.AutoStreamLinkCheck.Outcome
import com.nuvio.tv.core.smartscraper.AutoStreamResolver.Attempt
import com.nuvio.tv.core.smartscraper.AutoStreamTrackCheck.AudioTrack
import com.nuvio.tv.core.smartscraper.AutoStreamTrackCheck.Violation
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto Stream v2.0, Phase 2: the link check, the tracks check before the first picture, and the order sources are tried in. */
class AutoStreamVerificationTest {

    private val config = AutoStreamConfig()
    private val max = config.maxFileSizeBytes

    // ==================================================================== Resolve + verify (§8, §3.1)

    @Test
    fun `a 206 with the whole size at most 8 GB plays, over it doesn't`() {
        val ok = AutoStreamLinkCheck.evaluate(206, "bytes 0-1/7945689497", "2", knownSizeBytes = 7_945_689_497, maxBytes = max)
        assertTrue(ok.ok)
        assertEquals(7_945_689_497, ok.totalBytes)
        assertEquals(Outcome.OK, AutoStreamLinkCheck.evaluate(206, "bytes 0-1/$max", "2", null, max).outcome)
        assertEquals(Outcome.TOO_LARGE, AutoStreamLinkCheck.evaluate(206, "bytes 0-1/${max + 1}", "2", null, max).outcome)
        // A server that ignores Range: its Content-Length is the whole file.
        assertEquals(Outcome.TOO_LARGE, AutoStreamLinkCheck.evaluate(200, null, "${(9.0 * GB).toLong()}", null, max).outcome)
    }

    @Test
    fun `a size not given plays only when the link confirms it`() {
        assertEquals(Outcome.SIZE_UNCONFIRMED, AutoStreamLinkCheck.evaluate(206, "bytes 0-1/*", null, null, max).outcome)
        assertEquals(Outcome.OK, AutoStreamLinkCheck.evaluate(206, "bytes 0-1/*", null, (5.0 * GB).toLong(), max).outcome)
        assertEquals(Outcome.OK, AutoStreamLinkCheck.evaluate(206, "bytes 0-1/${(5.0 * GB).toLong()}", null, null, max).outcome)
    }

    @Test
    fun `the provider's failures and the file's are told apart`() {
        listOf(401, 403, 429, 500, 502, 503).forEach { code ->
            val result = AutoStreamLinkCheck.evaluate(code, null, null, null, max)
            assertEquals(Outcome.PROVIDER_ERROR, result.outcome)
            assertTrue(result.providerLevel)
        }
        listOf(404, 410, 416).forEach { code ->
            val result = AutoStreamLinkCheck.evaluate(code, null, null, null, max)
            assertEquals(Outcome.FILE_GONE, result.outcome)
            assertFalse(result.providerLevel)
        }
        assertTrue(AutoStreamLinkCheck.unreachable().providerLevel)
        // A 2 MB "download failed" clip in place of a 6 GB movie.
        val clip = AutoStreamLinkCheck.evaluate(206, "bytes 0-1/2000000", null, (6.0 * GB).toLong(), max)
        assertEquals(Outcome.PLACEHOLDER, clip.outcome)
        assertTrue(clip.providerLevel)
    }

    // ==================================================================== Before the first picture (§8.3, J)

    @Test
    fun `J - audio that turns out to be 7_1 fails the check, 5_1 or 2_0 pass`() {
        val english = setOf("en")
        assertEquals(Violation.AUDIO_LAYOUT, check(listOf(AudioTrack("eng", 8)), 3840, 2160, english))
        assertNull(check(listOf(AudioTrack("eng", 6)), 3840, 2160, english))
        assertNull(check(listOf(AudioTrack("en", 2)), 1920, 1080, english))
        // A 7.1 TrueHD with a 5.1 compatibility track in the same language: it has 5.1.
        assertNull(check(listOf(AudioTrack("eng", 8), AudioTrack("eng", 6)), 3840, 2160, english))
        // Mono isn't 5.1 or 2.0 either.
        assertEquals(Violation.AUDIO_LAYOUT, check(listOf(AudioTrack("eng", 1)), 1920, 1080, english))
        // What the player doesn't say can't fail the check.
        assertNull(check(listOf(AudioTrack("eng", null)), 1920, 1080, english))
        assertNull(check(emptyList(), null, null, english))
    }

    @Test
    fun `the video needs English or the title's own audio, untagged counts as maybe`() {
        val englishFilm = setOf("en")
        assertEquals(Violation.NO_WANTED_AUDIO, check(listOf(AudioTrack("spa", 6)), 1920, 1080, englishFilm))
        assertNull(check(listOf(AudioTrack("spa", 6), AudioTrack("eng", 6)), 1920, 1080, englishFilm))
        assertNull(check(listOf(AudioTrack("und", 6)), 1920, 1080, englishFilm))
        assertNull(check(listOf(AudioTrack(null, 6)), 1920, 1080, englishFilm))
        // A Japanese film in Japanese; Spanish chosen (until the dual-language engine): Spanish is fine.
        assertNull(check(listOf(AudioTrack("jpn", 2)), 1920, 1080, setOf("en", "ja")))
        assertNull(check(listOf(AudioTrack("es-419", 6)), 1920, 1080, setOf("en", "es")))
        // The layout that counts is the wanted language's: English 7.1 with Spanish 5.1 fails.
        assertEquals(Violation.AUDIO_LAYOUT, check(listOf(AudioTrack("eng", 8), AudioTrack("spa", 6)), 3840, 2160, englishFilm))
    }

    @Test
    fun `a picture below 480p fails, a scope 1080p film is 1080p`() {
        val tracks = listOf(AudioTrack("eng", 2))
        assertEquals(Violation.LOW_RESOLUTION, check(tracks, 640, 360, setOf("en")))
        assertNull(check(tracks, 720, 480, setOf("en")))
        assertEquals(VideoResolution.FHD_1080, AutoStreamTrackCheck.resolutionOf(1920, 800))
        assertEquals(VideoResolution.UHD_2160, AutoStreamTrackCheck.resolutionOf(3840, 1600))
    }

    @Test
    fun `the languages wanted are English, the title's own, and Spanish when chosen`() {
        assertEquals(setOf("en", "ja"), AutoStreamEngine.wantedAudioLanguages("ja", AudioLanguage.ENGLISH))
        assertEquals(setOf("en", "es"), AutoStreamEngine.wantedAudioLanguages("en", AudioLanguage.SPANISH))
        assertEquals(setOf("en"), AutoStreamEngine.wantedAudioLanguages(null, null))
        assertEquals(setOf("en", "fr", "es"), AutoStreamEngine.wantedAudioLanguages("fr", AudioLanguage.SPANISH))
    }

    @Test
    fun `language codes are compared in any form`() {
        assertEquals("eng", AutoStreamTrackCheck.iso3("en"))
        assertEquals("eng", AutoStreamTrackCheck.iso3("en-US"))
        assertEquals("spa", AutoStreamTrackCheck.iso3("es-419"))
        assertEquals("fra", AutoStreamTrackCheck.iso3("fre"))
        assertNull(AutoStreamTrackCheck.iso3("und"))
        assertNull(AutoStreamTrackCheck.iso3(""))
        assertNull(AutoStreamTrackCheck.iso3(null))
    }

    // ==================================================================== Trying sources in time (§8, §8.4, G)

    @Test
    fun `G - a provider that's down sends its other sources after the rest`() = runBlocking {
        val failed = ArrayList<String>()
        val found = AutoStreamResolver.firstPlayable(
            candidates = listOf(stream("a1", "Down"), stream("a2", "Down"), stream("b1", "Up")),
            deadlineAtMs = System.currentTimeMillis() + 10_000,
            attemptTimeoutMs = 2_500,
            maxAttempts = 5,
            providerOf = { it.addonName },
            onFailure = { stream, _ -> failed += stream.infoHash!! }
        ) { stream ->
            if (stream.addonName == "Down") Attempt.Failed("503", providerLevel = true) else Attempt.Ok(stream.infoHash)
        }
        assertEquals("b1", found?.stream?.infoHash)
        // The down provider's second source was never tried before the other provider's.
        assertEquals(listOf("a1"), failed)
    }

    @Test
    fun `G - one that times out is given up on after its time, the next plays`() = runBlocking {
        val started = System.currentTimeMillis()
        val found = AutoStreamResolver.firstPlayable(
            candidates = listOf(stream("slow", "Slow"), stream("fast", "Fast")),
            deadlineAtMs = started + 10_000,
            attemptTimeoutMs = 200,
            maxAttempts = 5,
            providerOf = { it.addonName }
        ) { stream ->
            if (stream.addonName == "Slow") {
                delay(5_000)
                Attempt.Ok("late")
            } else {
                Attempt.Ok("fast")
            }
        }
        assertEquals("fast", found?.value)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `no source is started past the deadline, a file's own failure doesn't skip its provider`() = runBlocking {
        val none = AutoStreamResolver.firstPlayable(
            candidates = listOf(stream("a", "A")),
            deadlineAtMs = System.currentTimeMillis() - 1,
            attemptTimeoutMs = 2_500,
            maxAttempts = 5,
            providerOf = { it.addonName }
        ) { Attempt.Ok(it) }
        assertNull(none)
        val tried = ArrayList<String>()
        val found = AutoStreamResolver.firstPlayable(
            candidates = listOf(stream("a1", "A"), stream("a2", "A"), stream("b1", "B")),
            deadlineAtMs = System.currentTimeMillis() + 10_000,
            attemptTimeoutMs = 2_500,
            maxAttempts = 5,
            providerOf = { it.addonName }
        ) { stream ->
            tried += stream.infoHash!!
            if (stream.infoHash == "a1") Attempt.Failed("404", providerLevel = false) else Attempt.Ok(stream.infoHash)
        }
        assertEquals("a2", found?.stream?.infoHash)
        assertEquals(listOf("a1", "a2"), tried)
    }

    // ==================================================================== The session (§8.2, §8.4)

    @Test
    fun `late results only add fallbacks, the deadline counts from Play`() {
        val first = stream("first", "A")
        val second = stream("second", "B")
        val startedAt = System.currentTimeMillis() - 3_000
        AutoStreamSession.begin("tt1", first, listOf(first, second), config, startedAtMs = startedAt, wantedLanguages = setOf("en"))
        val session = AutoStreamSession.current("tt1")!!
        assertEquals(listOf("second"), session.upcoming.map { it.infoHash })
        assertEquals(startedAt + 10_000, session.deadlineAtMs)
        assertFalse(session.pastDeadline(startedAt + 9_999))
        assertTrue(session.pastDeadline(startedAt + 10_000))
        // A late one is added after them; one already there or playing isn't added again.
        assertEquals(1, AutoStreamSession.appendLate("tt1", listOf(first, second, stream("late", "C"))))
        assertEquals(listOf("second", "late"), session.upcoming.map { it.infoHash })
        assertTrue(session.prePlayCheckPending)
        // Skipping to a checked source drops the ones tried before it.
        assertTrue(AutoStreamSession.advanceTo("tt1", session.upcoming[1], tried = listOf(session.upcoming[0])))
        assertEquals("late", session.current.infoHash)
        assertTrue(session.upcoming.isEmpty())
        assertTrue(session.prePlayCheckPending)
        AutoStreamSession.end()
        assertNull(AutoStreamSession.current("tt1"))
    }

    // ==================================================================== Helpers

    private fun check(audio: List<AudioTrack>, width: Int?, height: Int?, wanted: Set<String>) =
        AutoStreamTrackCheck.check(audio, width, height, wanted, config)

    private fun stream(hash: String, addon: String) = Stream(
        name = "[RD+] $addon",
        title = "Movie.2020.1080p.WEB-DL.DDP5.1.x264",
        description = null,
        url = "https://example.com/$hash",
        ytId = null,
        infoHash = hash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = addon,
        addonLogo = null
    )

    private companion object {
        const val GB = 1_073_741_824L
    }
}
