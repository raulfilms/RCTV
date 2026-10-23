package com.nuvio.tv.core.smartscraper

import com.nuvio.tv.domain.model.Stream

/**
 * The Auto Stream now playing: the source it started with and the title's next valid ones, in
 * Auto Stream's order, so the player moves on to the next when one fails to start or breaks,
 * without anyone choosing (never to another title or episode: it's only ever for [Session.videoId]).
 */
object AutoStreamSession {

    class Session internal constructor(
        val videoId: String,
        current: Stream,
        candidates: List<Stream>,
        val maxFallbacks: Int,
        val startupTimeoutMs: Long
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
        @Volatile var rebufferingRecorded: Boolean = false
            internal set
    }

    @Volatile private var session: Session? = null

    /** [current] is about to play for [videoId]; [candidates] are what to try next, in order. */
    @Synchronized
    fun begin(videoId: String, current: Stream, candidates: List<Stream>, config: AutoStreamConfig) {
        session = Session(
            videoId = videoId,
            current = current,
            candidates = candidates.filterNot { it.isSameSource(current) },
            maxFallbacks = config.maxFallbacks,
            startupTimeoutMs = config.startupTimeoutSeconds * 1_000L
        )
    }

    /** The session for [videoId], if Auto Stream started it. */
    fun current(videoId: String?): Session? = session?.takeIf { videoId != null && it.videoId == videoId }

    /** The next source to try for [videoId] (null: none left, or enough have been tried). */
    @Synchronized
    fun next(videoId: String?): Stream? {
        val current = current(videoId) ?: return null
        if (current.fallbacks >= current.maxFallbacks) return null
        val next = current.remaining.removeFirstOrNull() ?: return null
        current.current = next
        current.fallbacks += 1
        current.attempt += 1
        current.attemptStartedAtMs = System.currentTimeMillis()
        current.started = false
        current.rebufferingRecorded = false
        return next
    }

    /** The current source showed its first picture. */
    fun markStarted(videoId: String?) {
        current(videoId)?.started = true
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
