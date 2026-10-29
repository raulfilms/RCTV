package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream

/**
 * The Auto Stream now playing: the source it started with and the title's next valid ones, in
 * Auto Stream's order, so the player moves on to the next when one fails to start or breaks,
 * without anyone choosing (never to another title or episode: it's only ever for [Session.videoId]).
 *
 * v2.0 (§8): it also keeps when Play was pressed (the startup deadline counts from then), the
 * languages the video must have (checked with its tracks before the first picture), and the results
 * that came after the choice (only to fall back on, §8.2).
 */
object AutoStreamSession {

    class Session internal constructor(
        val videoId: String,
        current: Stream,
        candidates: List<Stream>,
        val maxFallbacks: Int,
        val startupTimeoutMs: Long,
        /** When Play was pressed. */
        val startedAtMs: Long,
        /** Past this, with no picture yet, no other source is started (§8.4). */
        val deadlineAtMs: Long,
        /** The video must have one of these audio languages (ISO codes); empty: not checked. */
        val wantedLanguages: Set<String>,
        /** The longest the first picture waits for the player to say its tracks. */
        val prePlayCheckTimeoutMs: Long,
        /** RCTV: the movie's version chosen in its Extras (Extended, Director's Cut); null: the regular one. */
        val edition: ReleaseEdition? = null
    ) {
        @Volatile var current: Stream = current
            internal set
        internal val remaining = ArrayDeque(candidates)
        @Volatile var fallbacks: Int = 0
            internal set
        /** Which try this is (each fallback is a new one). */
        @Volatile var attempt: Int = 0
            internal set
        @Volatile var attemptStartedAtMs: Long = System.currentTimeMillis()
            internal set
        /** The current source has shown its first picture. */
        @Volatile var started: Boolean = false
            internal set
        /** Some source has shown its first picture (a later failure resumes where it was). */
        @Volatile var everStarted: Boolean = false
            internal set
        @Volatile var rebufferingRecorded: Boolean = false
            internal set
        /** The current source's tracks haven't been checked yet (§8.3). */
        @Volatile var prePlayCheckPending: Boolean = true
            internal set
        /** The player was started paused for that check (mpv): it's let go once the check is done. */
        @Volatile var holdingStart: Boolean = false
            internal set
        /** The next source is being looked for (its link checked): another failure meanwhile changes nothing. */
        @Volatile var switching: Boolean = false
            internal set

        /** The next sources, in order (a copy). */
        val upcoming: List<Stream> get() = synchronized(this) { remaining.toList() }

        fun pastDeadline(now: Long = System.currentTimeMillis()): Boolean = now >= deadlineAtMs
    }

    @Volatile private var session: Session? = null

    /**
     * [current] is about to play for [videoId]; [candidates] are what to try next, in order.
     * [startedAtMs]: when Play was pressed (now by default).
     */
    @Synchronized
    fun begin(
        videoId: String,
        current: Stream,
        candidates: List<Stream>,
        config: AutoStreamConfig,
        startedAtMs: Long = System.currentTimeMillis(),
        wantedLanguages: Set<String> = emptySet(),
        edition: ReleaseEdition? = null
    ) {
        session = Session(
            videoId = videoId,
            current = current,
            candidates = candidates.filterNot { it.isSameSource(current) },
            maxFallbacks = config.maxFallbacks,
            startupTimeoutMs = config.startupTimeoutSeconds * 1_000L,
            startedAtMs = startedAtMs,
            deadlineAtMs = startedAtMs + (config.startupDeadlineSeconds * 1_000).toLong(),
            wantedLanguages = wantedLanguages,
            prePlayCheckTimeoutMs = (config.prePlayCheckTimeoutSeconds * 1_000).toLong(),
            edition = edition
        )
    }

    /** The session for [videoId], if Auto Stream started it. */
    fun current(videoId: String?): Session? = session?.takeIf { videoId != null && it.videoId == videoId }

    /** The next source to try for [videoId] (null: none left, or enough have been tried). */
    @Synchronized
    fun next(videoId: String?): Stream? {
        val current = current(videoId) ?: return null
        if (current.fallbacks >= current.maxFallbacks) return null
        val next = synchronized(current) { current.remaining.removeFirstOrNull() } ?: return null
        moveTo(current, next)
        return next
    }

    /**
     * [chosen] plays next for [videoId] (one of its upcoming sources, picked after checking its link):
     * it and the ones tried before it leave the queue. False when it isn't upcoming.
     */
    @Synchronized
    fun advanceTo(videoId: String?, chosen: Stream, tried: List<Stream>): Boolean {
        val current = current(videoId) ?: return false
        synchronized(current) {
            if (current.remaining.none { it.isSameSource(chosen) }) return false
            current.remaining.removeAll { candidate -> candidate.isSameSource(chosen) || tried.any { it.isSameSource(candidate) } }
        }
        moveTo(current, chosen)
        return true
    }

    /** How many more sources may be tried for [videoId]. */
    fun fallbacksLeft(videoId: String?): Int = current(videoId)?.let { (it.maxFallbacks - it.fallbacks).coerceAtLeast(0) } ?: 0

    private fun moveTo(current: Session, next: Stream) {
        current.current = next
        current.fallbacks += 1
        current.attempt += 1
        current.attemptStartedAtMs = System.currentTimeMillis()
        current.started = false
        current.rebufferingRecorded = false
        current.prePlayCheckPending = true
        current.holdingStart = false
        current.switching = false
    }

    /**
     * §8.2: sources found after the choice, only to fall back on (after the ones already lined up,
     * never instead of what plays). Returns how many were added.
     */
    @Synchronized
    fun appendLate(videoId: String?, late: List<Stream>): Int {
        val current = current(videoId) ?: return 0
        var added = 0
        synchronized(current) {
            late.forEach { stream ->
                if (stream.isSameSource(current.current) || current.remaining.any { it.isSameSource(stream) }) return@forEach
                current.remaining.addLast(stream)
                added++
            }
        }
        return added
    }

    /** The current source showed its first picture. */
    fun markStarted(videoId: String?) {
        current(videoId)?.let {
            it.started = true
            it.everStarted = true
        }
    }

    /** The current source's tracks were checked (or couldn't be: nothing more is waited for). */
    fun markPrePlayChecked(videoId: String?) {
        current(videoId)?.prePlayCheckPending = false
    }

    fun markRebufferingRecorded(videoId: String?) {
        current(videoId)?.rebufferingRecorded = true
    }

    /** Someone chose a source themselves, or the title changed: nothing more is tried on its own. */
    @Synchronized
    fun end() {
        session = null
    }

    private fun Stream.isSameSource(other: Stream): Boolean =
        url == other.url && infoHash == other.infoHash && ytId == other.ytId &&
            addonName == other.addonName && fileIdx == other.fileIdx
}
