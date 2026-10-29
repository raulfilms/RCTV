package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto Stream v2.0, Phase 1: the hard rules (§3) and the ranking (§4, §5), with the spec's acceptance tests A, B, C and J. */
class AutoStreamEngineTest {

    private val movie = SmartSourceRequest(title = "Movie", year = 2020, isSeries = false, runtimeMinutes = 120)

    // ==================================================================== A. Size boundary

    @Test
    fun `A - 8_00 GB plays, 8_01 GB and 9_0 GB never do`() {
        val result = rank(
            stream("8.00", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 8.00),
            stream("8.01", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 8.01),
            stream("9.0", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 9.0)
        )
        assertEquals(listOf("8.00"), hashes(result))
        assertEquals(AutoStreamReject.TOO_LARGE, reason(result, "8.01"))
        assertEquals(AutoStreamReject.TOO_LARGE, reason(result, "9.0"))
        // Exactly 8 × 1024³ bytes is in; one byte more isn't.
        val limit = AutoStreamConfig().maxFileSizeBytes
        assertEquals(8L * 1_073_741_824L, limit)
        val exact = rank(streamBytes("exact", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", limit), streamBytes("over", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", limit + 1))
        assertEquals(listOf("exact"), hashes(exact))
    }

    @Test
    fun `the size shown agrees with the rule (one unit, rounded up)`() {
        val release = { gb: Double -> ReleaseParser.parse(ReleaseParser.Input(names = listOf("Movie.2020.2160p.WEB-DL.DDP5.1.HEVC"), sizeBytes = (gb * GB).toLong())) }
        assertEquals("4K · 8.0 GB · 5.1", AutoStreamEngine.summary(release(8.00)))
        assertEquals("4K · 8.1 GB · 5.1", AutoStreamEngine.summary(release(8.01)))
        assertEquals("4K · 7.4 GB · 5.1", AutoStreamEngine.summary(release(7.4)))
        assertEquals(ParsedRelease.BYTES_PER_GB.toLong(), AutoStreamConfig().bytesPerGb)
        assertEquals((7.4 * GB).toLong(), ReleaseParser.sizeIn("💾 7.4 GB"))
    }

    // ==================================================================== B. The Batman (2022)

    private val batman = SmartSourceRequest(title = "The Batman", year = 2022, isSeries = false, runtimeMinutes = 176)

    private fun batmanStreams() = listOf(
        stream("too-big", "The.Batman.2022.2160p.WEB-DL.DDP5.1.HDR.HEVC", 9.1),
        stream("truehd-7.1", "The.Batman.2022.2160p.BluRay.TrueHD.7.1.Atmos.HEVC", 7.6),
        stream("uncached", "The.Batman.2022.2160p.WEB-DL.DDP5.1.HEVC", 5.9, name = "[RD download] Torrentio\n4k"),
        stream("1989", "Batman.1989.2160p.BluRay.DTS-HD.MA.5.1.HEVC", 6.0),
        stream("atmos", "The.Batman.2022.2160p.WEB-DL.DDP5.1.Atmos.HDR.HEVC", 7.4),
        stream("stereo", "The.Batman.2022.2160p.WEB-DL.AAC2.0.HEVC", 6.9),
        stream("1080", "The.Batman.2022.1080p.WEB-DL.DDP5.1.x264", 5.2)
    )

    @Test
    fun `B - The Batman picks 4K 7_4 GB DD+ 5_1 Atmos HDR, each other for its rule`() {
        val tv = SmartRankingContext(device = DeviceProfile(maxResolution = VideoResolution.UHD_2160, supportsHdr10 = true, isKnown = true))
        for (context in listOf(SmartRankingContext.DEFAULT, tv)) {
            val result = AutoStreamEngine.rank(batmanStreams().map(SourceProviderAdapters::normalize), batman, context)
            assertEquals("atmos", result.best?.stream?.infoHash)
            assertEquals(listOf("atmos", "stereo", "1080"), hashes(result))
            assertEquals(AutoStreamReject.TOO_LARGE, reason(result, "too-big"))
            assertEquals(AutoStreamReject.AUDIO_NOT_ALLOWED, reason(result, "truehd-7.1"))
            assertEquals(AutoStreamReject.NOT_CACHED, reason(result, "uncached"))
            assertEquals(AutoStreamReject.WRONG_CONTENT, reason(result, "1989"))
        }
    }

    @Test
    fun `B - it steps down to 1080p only when the connection is below the 4K's bitrate times the headroom`() {
        // 7.4 GB over 176 min ≈ 6.0 Mbps × 1.5 ≈ 9.0 Mbps; 1080p 5.2 GB ≈ 4.2 Mbps × 1.5 ≈ 6.3 Mbps.
        val enough = SmartRankingContext(connection = ConnectionEstimate(ConnectionProfile.SLOW, availableMbps = 10.0))
        val short = SmartRankingContext(connection = ConnectionEstimate(ConnectionProfile.SLOW, availableMbps = 8.0))
        val normalized = batmanStreams().map(SourceProviderAdapters::normalize)
        assertEquals("atmos", AutoStreamEngine.rank(normalized, batman, enough).best?.stream?.infoHash)
        val stepped = AutoStreamEngine.rank(normalized, batman, short)
        assertEquals("1080", stepped.best?.stream?.infoHash)
        // The 4Ks stay only as the last resort.
        assertEquals(listOf("1080", "atmos", "stereo"), hashes(stepped))
        assertFalse(stepped.candidates.last().tierAllowed)
    }

    // ==================================================================== C. Episodes

    @Test
    fun `C - a request for S02E04 never plays S01E04`() {
        val request = SmartSourceRequest(title = "Show", year = null, isSeries = true, season = 2, episode = 4)
        val result = AutoStreamEngine.rank(
            listOf(
                stream("s1", "Show.S01E04.1080p.WEB-DL.DDP5.1.x264", 1.8),
                stream("s2", "Show.S02E04.1080p.WEB-DL.DDP5.1.x264", 1.8),
                stream("s2e5", "Show.S02E05.1080p.WEB-DL.DDP5.1.x264", 1.8)
            ).map(SourceProviderAdapters::normalize),
            request
        )
        assertEquals(listOf("s2"), hashes(result))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(result, "s1"))
        assertEquals(AutoStreamReject.WRONG_CONTENT, reason(result, "s2e5"))
    }

    @Test
    fun `a season pack plays only its episode's own file, by that file's size`() {
        val request = SmartSourceRequest(title = "Show", year = null, isSeries = true, season = 2, episode = 4)
        val normalized = listOf(
            // The whole season, no file of it named: never.
            stream("pack", "Show.S02.1080p.WEB-DL.DDP5.1.x264", 14.0),
            // Its episode's file named inside the pack (Torrentio's title: the torrent, then the file).
            stream("file", "Show.S02.1080p.WEB-DL.DDP5.1.x264\nShow.S02E04.1080p.WEB-DL.DDP5.1.x264.mkv", 1.6, fileIdx = 3),
            // An episode number with no season isn't this exact episode.
            stream("no-season", "Show.Episode.4.1080p.WEB-DL.DDP5.1.x264", 1.6)
        ).map(SourceProviderAdapters::normalize)
        val result = AutoStreamEngine.rank(normalized, request)
        assertEquals(listOf("file"), hashes(result))
        assertEquals(AutoStreamReject.EPISODE_NOT_IDENTIFIED, reason(result, "pack"))
        assertEquals(AutoStreamReject.EPISODE_NOT_IDENTIFIED, reason(result, "no-season"))
        // A pack its addon points one file of: the pack's size isn't the file's, so it's checked first.
        val pointed = SourceProviderAdapters.normalize(stream("pointed", "Show.S02.1080p.WEB-DL.DDP5.1.x264", 14.0, fileIdx = 3))
        assertEquals(AutoStreamReject.SIZE_UNKNOWN, reason(AutoStreamEngine.rank(listOf(pointed), request), "pointed"))
        val checked = AutoStreamEngine.rank(listOf(pointed), request, canVerifySize = true)
        assertEquals(listOf("pointed"), hashes(checked))
        assertFalse(checked.best!!.sizeKnown)
        assertTrue(checked.best!!.needsVerification)
    }

    // ==================================================================== J. Unknown audio (its ranking; the check before the first picture is Phase 2)

    @Test
    fun `J - audio not said may play, after the 5_1 and 2_0 ones of its resolution, and is checked first`() {
        val result = rank(
            stream("unsaid", "Movie.2020.2160p.WEB-DL.HEVC", 5.5),
            stream("stereo", "Movie.2020.2160p.WEB-DL.AAC2.0.HEVC", 5.4),
            stream("hd", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.5)
        )
        assertEquals(listOf("stereo", "unsaid", "hd"), hashes(result))
        val unsaid = result.candidates.first { it.stream.infoHash == "unsaid" }
        assertFalse(unsaid.verified)
        assertTrue(unsaid.needsVerification)
        val strict = AutoStreamEngine.rank(
            listOf(SourceProviderAdapters.normalize(stream("unsaid", "Movie.2020.2160p.WEB-DL.HEVC", 5.5))),
            movie,
            SmartRankingContext(config = SmartScraperConfig(autoStream = AutoStreamConfig(allowUnverifiedAudio = false)))
        )
        assertEquals(AutoStreamReject.AUDIO_UNVERIFIED, reason(strict, "unsaid"))
    }

    // ==================================================================== Other hard rules

    @Test
    fun `480p is the last resort, 360p and CAM never, an unknown resolution the lowest`() {
        val result = rank(
            stream("360", "Movie.2020.360p.WEB-DL.AAC2.0.x264", 0.5),
            stream("480", "Movie.2020.480p.WEB-DL.AAC2.0.x264", 0.9),
            stream("unknown", "Movie.2020.WEB-DL.AAC2.0.x264", 1.2, name = "[RD+] Torrentio"),
            stream("720", "Movie.2020.720p.WEB-DL.AAC2.0.x264", 1.5),
            stream("cam", "Movie.2020.1080p.CAM.AAC2.0.x264", 1.5)
        )
        assertEquals(listOf("720", "480", "unknown"), hashes(result))
        assertEquals(AutoStreamReject.LOW_RESOLUTION, reason(result, "360"))
        assertEquals(AutoStreamReject.LOW_QUALITY_SOURCE, reason(result, "cam"))
    }

    @Test
    fun `only 5_1 and 2_0 - DD+ 5_1 Atmos is 5_1, any 7_1 never`() {
        val result = rank(
            stream("ddp-atmos", "Movie.2020.2160p.WEB-DL.DDP5.1.Atmos.HEVC", 6.0),
            stream("truehd", "Movie.2020.2160p.BluRay.TrueHD.7.1.Atmos.HEVC", 7.0),
            stream("dtsx", "Movie.2020.2160p.BluRay.DTS-X.7.1.HEVC", 7.0),
            stream("dtshd", "Movie.2020.1080p.BluRay.DTS-HD.MA.7.1.x264", 7.0)
        )
        assertEquals(listOf("ddp-atmos"), hashes(result))
        listOf("truehd", "dtsx", "dtshd").forEach { assertEquals(AutoStreamReject.AUDIO_NOT_ALLOWED, reason(result, it)) }
    }

    @Test
    fun `only what its addon marks ready on the debrid service`() {
        val result = rank(
            stream("ready", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.0),
            stream("not-said", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 6.0, name = "Torrentio\n4k"),
            stream("downloading", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 6.0, name = "[RD download] Torrentio\n4k")
        )
        assertEquals(listOf("ready"), hashes(result))
        assertEquals(AutoStreamReject.NOT_CACHED, reason(result, "not-said"))
        assertEquals(AutoStreamReject.NOT_CACHED, reason(result, "downloading"))
    }

    @Test
    fun `a Spanish-only file is never the video, unless Spanish is the title's own language`() {
        val latino = stream("latino", "Movie.2020.1080p.WEB-DL.DD5.1.x264.Latino", 3.1)
        val dual = stream("dual", "Movie.2020.1080p.WEB-DL.DD5.1.x264.Dual.Latino-Ingles", 3.0)
        val english = movie.copy(originalLanguage = AudioLanguage.ENGLISH)
        val result = AutoStreamEngine.rank(listOf(latino, dual).map(SourceProviderAdapters::normalize), english)
        assertEquals(listOf("dual"), hashes(result))
        assertEquals(AutoStreamReject.NO_ORIGINAL_AUDIO, reason(result, "latino"))
        assertTrue(result.best!!.embeddedSpanish)
        assertTrue(result.best!!.originalAudioConfirmed)
        val spanishFilm = movie.copy(originalLanguage = AudioLanguage.SPANISH)
        assertEquals(setOf("latino", "dual"), hashes(AutoStreamEngine.rank(listOf(latino, dual).map(SourceProviderAdapters::normalize), spanishFilm)).toSet())
    }

    @Test
    fun `hard rules are never relaxed - nothing within them, no automatic stream`() {
        val result = rank(
            stream("big", "Movie.2020.2160p.BluRay.TrueHD.7.1.HEVC", 12.0),
            stream("bigger", "Movie.2020.2160p.BluRay.REMUX.TrueHD.7.1.HEVC", 40.0)
        )
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.chain.isEmpty())
        assertNull(result.best)
    }

    // ==================================================================== Ranking

    @Test
    fun `5_1 over 2_0, then E-AC-3 over DTS over AC-3 over AAC`() {
        val result = rank(
            stream("aac", "Movie.2020.1080p.WEB-DL.AAC5.1.x264", 4.0),
            stream("dd", "Movie.2020.1080p.WEB-DL.DD5.1.x264", 4.0),
            stream("stereo-ddp", "Movie.2020.1080p.WEB-DL.DDP2.0.x264", 4.0),
            stream("dts", "Movie.2020.1080p.BluRay.DTS.5.1.x264", 4.0),
            stream("ddp", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.0)
        )
        assertEquals(listOf("ddp", "dts", "dd", "aac", "stereo-ddp"), hashes(result))
    }

    @Test
    fun `an audio format this box can't play comes after the ones it can`() {
        val box = SmartRankingContext(device = DeviceProfile(isKnown = true, audioOutputs = setOf(AudioCodec.AAC, AudioCodec.DD, AudioCodec.LPCM)))
        val result = rank(
            stream("ddp", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.0),
            stream("aac", "Movie.2020.1080p.WEB-DL.AAC5.1.x264", 4.0),
            context = box
        )
        assertEquals(listOf("aac", "ddp"), hashes(result))
    }

    @Test
    fun `within the margin the more reliable, faster source beats a slightly bigger file`() {
        // 4K 5.6 GB answering in 2 s from a source that starts, against 5.8 GB answering in 8 s.
        val context = SmartRankingContext(
            providerStats = mapOf(
                "Fast" to ProviderStats(successes = 30.0, failures = 1.0, averageResponseMs = 2_000.0),
                "Slow" to ProviderStats(successes = 3.0, failures = 3.0, averageResponseMs = 8_000.0)
            )
        )
        val comparable = rank(
            stream("5.8", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.8, addon = "Slow", name = "[RD+] Slow"),
            stream("5.6", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.6, addon = "Fast", name = "[RD+] Fast"),
            context = context
        )
        assertEquals("5.6", comparable.best?.stream?.infoHash)
        // Not comparable (over 15% apart): the better picture first, never the smaller for being so.
        val apart = rank(
            stream("7.8", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 7.8, addon = "Slow", name = "[RD+] Slow"),
            stream("5.6", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.6, addon = "Fast", name = "[RD+] Fast"),
            context = context
        )
        assertEquals("7.8", apart.best?.stream?.infoHash)
    }

    @Test
    fun `among comparable files one that already has Spanish comes first`() {
        val result = rank(
            stream("english", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.2),
            stream("dual", "Movie.2020.1080p.WEB-DL.DDP5.1.x264.Dual.Latino-Ingles", 4.0)
        )
        assertEquals("dual", result.best?.stream?.infoHash)
        // Not comparable: the picture first.
        val apart = rank(
            stream("english", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.2),
            stream("dual", "Movie.2020.1080p.WEB-DL.DDP5.1.x264.Dual.Latino-Ingles", 3.0)
        )
        assertEquals("english", apart.best?.stream?.infoHash)
    }

    @Test
    fun `a size not given is checked from the link and comes after known sizes`() {
        val result = AutoStreamEngine.rank(
            listOf(
                stream("unknown", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", null),
                stream("known", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.0)
            ).map(SourceProviderAdapters::normalize),
            movie,
            canVerifySize = true
        )
        assertEquals(listOf("known", "unknown"), hashes(result))
        // Until that check exists (Phase 2), it isn't played at all.
        assertEquals(AutoStreamReject.SIZE_UNKNOWN, reason(rank(stream("unknown", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", null)), "unknown"))
    }

    @Test
    fun `a 1080p TV without HDR gets 1080p, then a 4K without HDR, never 4K HDR first`() {
        val tv = SmartRankingContext(device = DeviceProfile(maxResolution = VideoResolution.FHD_1080, supportsHdr10 = false, isKnown = true))
        val result = rank(
            stream("4khdr", "Movie.2020.2160p.WEB-DL.DDP5.1.HDR10.HEVC", 5.6),
            stream("4ksdr", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.4),
            stream("1080", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.0),
            stream("720", "Movie.2020.720p.WEB-DL.DDP5.1.x264", 2.0),
            context = tv
        )
        assertEquals(listOf("1080", "4ksdr", "720", "4khdr"), hashes(result))
    }

    @Test
    fun `a 4K HDR TV gets 4K HDR, Dolby Vision only when it shows it, at once from a reliable source`() {
        val tv = SmartRankingContext(
            device = DeviceProfile(maxResolution = VideoResolution.UHD_2160, supportsHdr10 = true, supportsDolbyVision = false, isKnown = true),
            providerStats = mapOf("Torrentio RD" to ProviderStats(successes = 20.0, averageResponseMs = 1_200.0))
        )
        val result = rank(
            stream("dvonly", "Movie.2020.2160p.WEB-DL.DDP5.1.DV.HEVC", 5.6),
            stream("hdr10", "Movie.2020.2160p.WEB-DL.DDP5.1.HDR10.HEVC", 5.5),
            stream("1080", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.0),
            context = tv
        )
        assertEquals(listOf("hdr10", "1080", "dvonly"), hashes(result))
        assertTrue(result.bestIsIdeal(tv))
        // Not at once: a 1080p on a 4K TV, or a source not known to start reliably.
        assertFalse(rank(stream("1080", "Movie.2020.1080p.WEB-DL.DDP5.1.x264", 4.0), context = tv).bestIsIdeal(tv))
        val unknownSource = tv.copy(providerStats = emptyMap())
        assertFalse(rank(stream("hdr10", "Movie.2020.2160p.WEB-DL.DDP5.1.HDR10.HEVC", 5.5), context = unknownSource).bestIsIdeal(unknownSource))
    }

    @Test
    fun `the same file from several services is one choice, its copy in the servers' order first`() {
        val context = SmartRankingContext(
            providerStats = mapOf(
                "Torrentio TB" to ProviderStats(successes = 2.0, failures = 2.0),
                "Torrentio PM" to ProviderStats(successes = 10.0, failures = 0.0)
            )
        )
        val result = rank(
            stream("same", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.5, name = "[RD download] Torrentio\n4k", addon = "Torrentio RD"),
            stream("same", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.5, name = "[TB+] Torrentio\n4k", addon = "Torrentio TB"),
            stream("same", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.5, name = "[PM+] Torrentio\n4k", addon = "Torrentio PM"),
            context = context
        )
        assertEquals(1, result.candidates.size)
        // RCTV: TorBox, then Real-Debrid (not ready here), then Premiumize, whatever their reliability.
        assertEquals("Torrentio TB", result.best!!.stream.addonName)
        assertEquals(listOf("Torrentio PM"), result.best!!.copies.map { it.stream.addonName })
        assertEquals(2, result.chain.size)
    }

    @Test
    fun `Spanish chosen - a Spanish stream first until the dual-language engine, English chosen - never a Spanish-only one`() {
        val english4k = stream("en", "Movie.2020.2160p.WEB-DL.DDP5.1.HEVC", 5.6)
        val latino = stream("es", "Movie.2020.1080p.WEB-DL.DD5.1.x264", 3.1, name = "LatinoDebrid\n1080p", addon = "LatinoDebrid")
        val normalized = listOf(english4k, latino).map(SourceProviderAdapters::normalize)
        val spanish = AutoStreamEngine.rank(normalized, movie.copy(preferredLanguage = AudioLanguage.SPANISH, originalLanguage = AudioLanguage.ENGLISH))
        assertEquals(listOf("es", "en"), hashes(spanish))
        assertEquals(AutoStreamPool.SPANISH, spanish.best!!.pool)
        val english = AutoStreamEngine.rank(normalized, movie.copy(preferredLanguage = AudioLanguage.ENGLISH, originalLanguage = AudioLanguage.ENGLISH))
        assertEquals(listOf("en"), hashes(english))
        assertEquals(AutoStreamReject.NO_ORIGINAL_AUDIO, reason(english, "es"))
    }

    @Test
    fun `every threshold of section 13 is configuration with the spec's default`() {
        val c = AutoStreamConfig()
        assertEquals(8.0, c.maxFileSizeGb, 0.0)
        assertEquals(1_073_741_824L, c.bytesPerGb)
        assertEquals(
            listOf(VideoResolution.UHD_2160, VideoResolution.QHD_1440, VideoResolution.FHD_1080, VideoResolution.HD_720, VideoResolution.SD),
            c.resolutions
        )
        assertEquals(setOf(AudioChannels.CH_5_1, AudioChannels.CH_2_0), c.allowedChannels)
        assertTrue(c.requireCached)
        assertEquals(10.0, c.startupDeadlineSeconds, 0.0)
        assertEquals(4.0, c.providerTimeoutSeconds, 0.0)
        assertEquals(2.5, c.earlyDecisionSeconds, 0.0)
        assertEquals(0.90, c.earlyDecisionMinReliability, 0.0)
        assertEquals(2.5, c.resolveVerifyTimeoutSeconds, 0.0)
        assertEquals(0.15, c.comparableBitrateMargin, 0.0)
        assertEquals(1.5, c.bitrateHeadroom, 0.0)
        assertEquals("en", c.preferredAudioLanguage)
        assertEquals(listOf("es-419", "es-ES"), c.spanishVariantOrder)
        // RCTV: the servers' order (AIO Stream first).
        assertEquals(listOf("AIO Stream (Spanish)", "LatinoDebrid", "Addon LATAM"), c.spanishPool)
        assertEquals(3, c.donorProbeCount)
        assertEquals(4.0, c.donorProbeTimeoutSeconds, 0.0)
        assertEquals(1.0, c.donorDurationToleranceSeconds, 0.0)
        assertEquals(20, c.donorIdleBufferSeconds)
        assertTrue(c.autoSwitchWhenSpanishReady)
        assertEquals(300, c.audioSwitchMaxGapMs)
        assertEquals(40, c.syncTargetMs)
        assertEquals(250, c.driftCheckIntervalMs)
        assertEquals(250, c.driftHardSeekMs)
        assertEquals(50, c.manualOffsetStepMs)
        assertEquals(5000, c.manualOffsetRangeMs)
        assertFalse(c.autoOffsetDetection)
        assertEquals(14, c.reliabilityHalfLifeDays)
        assertEquals(30, c.resultCacheTtlMinutes)
    }

    @Test
    fun `audio languages are read from words and flags, never from subtitles`() {
        assertEquals(
            setOf(AudioLanguage.SPANISH, AudioLanguage.ENGLISH),
            ReleaseParser.parseName("Movie.2020.1080p.WEB-DL.Dual.Latino-Ingles.DD5.1").audioLanguages
        )
        assertEquals(emptySet<AudioLanguage>(), ReleaseParser.parseName("Movie.2020.1080p.WEB-DL.Sub.Español").audioLanguages)
        assertEquals(setOf(AudioLanguage.SPANISH), ReleaseParser.audioLanguagesIn("💾 2 GB 🇲🇽"))
        assertEquals(setOf(AudioLanguage.SPANISH), ReleaseParser.parseName("Pelicula.2020.1080p.Castellano.AC3").audioLanguages)
    }

    // ==================================================================== Helpers

    private fun rank(vararg streams: Stream, context: SmartRankingContext = SmartRankingContext.DEFAULT) =
        AutoStreamEngine.rank(streams.map(SourceProviderAdapters::normalize), movie, context)

    private fun hashes(result: AutoStreamResult) = result.candidates.map { it.stream.infoHash }

    private fun reason(result: AutoStreamResult, hash: String) =
        result.rejected.first { it.first.stream.infoHash == hash }.second

    private fun stream(
        hash: String,
        release: String,
        sizeGb: Double?,
        name: String = "[RD+] Torrentio\n4k",
        addon: String = "Torrentio RD",
        fileIdx: Int? = null
    ) = streamBytes(hash, release, sizeGb?.let { (it * GB).toLong() }, name, addon, fileIdx)

    private fun streamBytes(
        hash: String,
        release: String,
        bytes: Long?,
        name: String = "[RD+] Torrentio\n4k",
        addon: String = "Torrentio RD",
        fileIdx: Int? = null
    ) = Stream(
        name = name,
        title = release,
        description = null,
        url = "https://example.com/$hash/$addon",
        ytId = null,
        infoHash = hash,
        fileIdx = fileIdx,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoSize = bytes
        ),
        addonName = addon,
        addonLogo = null
    )

    private companion object {
        const val GB = 1_073_741_824L
    }
}
