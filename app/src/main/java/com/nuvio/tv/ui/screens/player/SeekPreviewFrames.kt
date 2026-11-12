package com.nuvio.tv.ui.screens.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nuvio.tv.core.player.SeekPreviewService
import com.nuvio.tv.core.player.SeekrEntryPoint
import com.nuvio.tv.core.player.SeekrPreviews
import com.nuvio.tv.core.player.SeekrTitle
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** RCTV (seek preview): what the window over the timeline shows while it's moved. */
data class SeekPreviewUi(
    /** Where the timeline is (its time is shown over the picture). */
    val positionMs: Long,
    /** The picture there (or the nearest one read so far); null until one comes. */
    val image: ImageBitmap?,
    /** This movie gives pictures: false shows only the time (its file can't be read for them). */
    val showsPictures: Boolean
)

/**
 * RCTV (seek preview): the pictures for the timeline's preview window, asked of
 * [SeekPreviewService] (a helper process) one at a time: while one is being read, only the latest
 * moment the remote reached is kept to read next, so moving fast never piles requests up. They're
 * kept in a small cache per movie ([CACHE_SIZE] pictures, about 4 MB) and let go when the movie
 * changes or the player closes. A movie whose file can't give them, or that takes too long, falls
 * back to showing only the time; all calls are on the main thread.
 */
internal class SeekPreviewFrames(context: Context, private val scope: CoroutineScope) {

    private val app = context.applicationContext

    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image.asStateFlow()

    private val _showsPictures = MutableStateFlow(false)
    val showsPictures: StateFlow<Boolean> = _showsPictures.asStateFlow()

    private var streamUrl: String? = null
    private var streamHeaders: Map<String, String> = emptyMap()
    private var unsupported = false
    private var failures = 0
    private val cache = object : LinkedHashMap<Long, ImageBitmap>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?): Boolean = size > CACHE_SIZE
    }
    private var inFlight: Long? = null
    private var wanted: Long? = null
    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    private var timeoutJob: Job? = null
    private var idleJob: Job? = null
    private var lastAskedAtMs = 0L
    private var timeouts = 0

    /** RCTV: Seekr's pictures (the account's key, set on the website), when it has the title. */
    private val seekr: SeekrPreviews? = runCatching {
        dagger.hilt.android.EntryPointAccessors.fromApplication(app, SeekrEntryPoint::class.java).seekrPreviews()
    }.getOrNull()
    private var seekrTrack: SeekrPreviews.Track? = null
    private var seekrLookup: Job? = null
    /** Seekr was asked about this movie (whatever it answered). */
    private var seekrAsked = false
    private var seekrWanted: Long? = null
    private var seekrReading = false

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == SeekPreviewService.MSG_RESULT) onResult(message.data)
        true
    })

    /**
     * The timeline was moved to [positionMs] of the movie at [url]; [eligible]: its source can give
     * pictures. RCTV: [title] (a movie or an episode, [durationMs] long) is asked of Seekr first.
     */
    fun show(
        url: String,
        headers: Map<String, String>,
        positionMs: Long,
        eligible: Boolean,
        title: SeekrTitle? = null,
        durationMs: Long = 0L
    ) {
        if (url != streamUrl) reset(url, headers)
        if (showSeekr(title, durationMs, positionMs)) return
        if (!eligible || unsupported || crashes.get() >= MAX_CRASHES || isDisabledOnDevice()) {
            _showsPictures.value = false
            return
        }
        _showsPictures.value = true
        touchIdle()
        val bucket = SeekPreviewSources.bucketOf(positionMs)
        val cached = cache[bucket]
        if (cached != null) {
            _image.value = cached
            wanted = null
            return
        }
        // Meanwhile the nearest picture read so far, if it's close; else an empty window.
        _image.value = cache.entries.filter { abs(it.key - bucket) <= NEAR_MS }
            .minByOrNull { abs(it.key - bucket) }?.value
        wanted = bucket
        if (inFlight == null) ask(bucket)
    }

    /** The player had a decoding error: if a picture was just being read, previews may be the cause. */
    fun notePlaybackDecoderError() {
        val askedAt = lastAskedAtMs
        if (askedAt == 0L || SystemClock.elapsedRealtime() - askedAt > SUSPECT_WINDOW_MS) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_SUSPECT_ERRORS, 0) + 1
        prefs.edit().putInt(KEY_SUSPECT_ERRORS, count).apply()
        Log.w(SeekPreviewService.TAG, "A decoding error right after a preview ($count)")
        if (count >= MAX_SUSPECT_ERRORS) {
            disabledOnDevice = true
            markUnsupported()
        }
    }

    /** The player closed: everything is let go. */
    fun close() {
        reset(null, emptyMap())
        idleJob?.cancel()
        idleJob = null
        unbind()
    }

    private fun reset(url: String?, headers: Map<String, String>) {
        seekrLookup?.cancel()
        seekrLookup = null
        seekrTrack = null
        seekrAsked = false
        seekrWanted = null
        streamUrl = url
        streamHeaders = headers
        unsupported = false
        failures = 0
        timeouts = 0
        cache.clear()
        _image.value = null
        _showsPictures.value = false
        inFlight = null
        wanted = null
        timeoutJob?.cancel()
        timeoutJob = null
    }

    /**
     * RCTV: true when Seekr has this movie's pictures (the one at [positionMs] shows, or is on its
     * way). The first time, Seekr is asked meanwhile and the TV's own pictures show until it answers.
     */
    private fun showSeekr(title: SeekrTitle?, durationMs: Long, positionMs: Long): Boolean {
        val client = seekr ?: return false
        if (title == null || durationMs <= 0L || !client.hasKey) return false
        seekrWanted = positionMs
        val track = seekrTrack
        if (track == null) {
            if (!seekrAsked && seekrLookup == null) {
                seekrLookup = scope.launch(Dispatchers.Main.immediate) {
                    val found = try {
                        client.track(title, durationMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    seekrAsked = true
                    seekrLookup = null
                    seekrTrack = found
                    // The timeline is still out: its picture from Seekr now.
                    if (found != null && seekrWanted != null) readSeekr(client, found)
                }
            }
            return false
        }
        readSeekr(client, track)
        return true
    }

    /** RCTV: Seekr's picture where the timeline is now (one read at a time: only the latest place). */
    private fun readSeekr(client: SeekrPreviews, track: SeekrPreviews.Track) {
        _showsPictures.value = true
        if (seekrReading) return
        seekrReading = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                while (true) {
                    val position = seekrWanted ?: break
                    seekrWanted = null
                    val picture = client.pictureAt(track, position)
                    if (picture != null && seekrTrack === track) _image.value = picture
                }
            } finally {
                seekrReading = false
            }
        }
    }

    private fun ask(bucket: Long) {
        inFlight = bucket
        val target = service
        if (target == null) {
            bind() // sent once it's connected
            return
        }
        val url = streamUrl ?: return
        val request = Message.obtain(null, SeekPreviewService.MSG_FRAME).apply {
            replyTo = replies
            data = Bundle().apply {
                putString(SeekPreviewService.KEY_URL, url)
                putBundle(SeekPreviewService.KEY_HEADERS, Bundle().apply {
                    streamHeaders.forEach { (key, value) -> putString(key, value) }
                })
                putLong(SeekPreviewService.KEY_POSITION, bucket)
                putInt(SeekPreviewService.KEY_WIDTH, SeekPreviewService.DEFAULT_WIDTH)
                putInt(SeekPreviewService.KEY_HEIGHT, SeekPreviewService.DEFAULT_HEIGHT)
            }
        }
        try {
            target.send(request)
        } catch (e: Exception) {
            inFlight = null
            service = null
            fail()
            return
        }
        lastAskedAtMs = SystemClock.elapsedRealtime()
        timeoutJob?.cancel()
        timeoutJob = scope.launch(Dispatchers.Main.immediate) {
            delay(if (cache.isEmpty()) FIRST_PICTURE_TIMEOUT_MS else PICTURE_TIMEOUT_MS)
            if (inFlight == bucket) {
                inFlight = null
                // A reader stuck on this file: after two, only the time is shown for this movie.
                if (++timeouts >= MAX_TIMEOUTS) markUnsupported() else askWanted(bucket)
            }
        }
    }

    private fun onResult(data: Bundle?) {
        val bucket = data?.getLong(SeekPreviewService.KEY_POSITION, -1L) ?: return
        if (bucket != inFlight) return // answered after its time ran out
        timeoutJob?.cancel()
        inFlight = null
        if (data.getBoolean(SeekPreviewService.KEY_UNSUPPORTED, false)) {
            markUnsupported()
            return
        }
        val picture = data.getByteArray(SeekPreviewService.KEY_IMAGE)?.let(::decode)
        if (picture != null) {
            failures = 0
            cache[bucket] = picture
            // RCTV: once Seekr's pictures show, the TV's own don't replace them.
            if (_showsPictures.value && seekrTrack == null) _image.value = picture
        } else if (++failures >= MAX_FAILURES) {
            markUnsupported()
            return
        }
        askWanted(bucket)
    }

    /** After [done], the latest moment the remote reached (if it's still to be read). */
    private fun askWanted(done: Long) {
        val next = wanted ?: return
        if (next == done) {
            wanted = null
            return
        }
        val cached = cache[next]
        if (cached != null) {
            _image.value = cached
            wanted = null
        } else {
            ask(next)
        }
    }

    private fun fail() {
        if (++failures >= MAX_FAILURES) markUnsupported() else wanted?.let(::ask)
    }

    private fun markUnsupported() {
        unsupported = true
        _showsPictures.value = false
        inFlight = null
        wanted = null
        timeoutJob?.cancel()
        timeoutJob = null
        unbind()
    }

    private fun decode(bytes: ByteArray): ImageBitmap? = try {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }

    private fun bind() {
        if (connection != null) return
        val created = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (connection !== this) return
                service = binder?.let(::Messenger)
                inFlight?.let(::ask)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The helper process closed (a crash there, or the TV needed its memory).
                service = null
                if (inFlight != null) {
                    inFlight = null
                    timeoutJob?.cancel()
                    val count = crashes.incrementAndGet()
                    Log.w(SeekPreviewService.TAG, "The preview reader closed while reading ($count)")
                    if (count >= MAX_CRASHES) markUnsupported() else fail()
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                unbind()
                inFlight = null
            }

            override fun onNullBinding(name: ComponentName?) = markUnsupported()
        }
        connection = created
        val bound = try {
            // Waived priority: under memory pressure the TV closes the helper, never the app.
            app.bindService(
                Intent(app, SeekPreviewService::class.java),
                created,
                Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY
            )
        } catch (e: Exception) {
            false
        }
        if (!bound) {
            connection = null
            markUnsupported()
        }
    }

    private fun unbind() {
        val old = connection ?: return
        connection = null
        service = null
        runCatching { app.unbindService(old) }
    }

    /** Pictures stop being read for a while: the helper lets the file go (the pictures stay). */
    private fun touchIdle() {
        idleJob?.cancel()
        idleJob = scope.launch(Dispatchers.Main.immediate) {
            delay(IDLE_RELEASE_MS)
            if (inFlight == null) unbind()
        }
    }

    private fun isDisabledOnDevice(): Boolean {
        disabledOnDevice?.let { return it }
        val disabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SUSPECT_ERRORS, 0) >= MAX_SUSPECT_ERRORS
        disabledOnDevice = disabled
        return disabled
    }

    internal companion object {
        private const val CACHE_SIZE = 36
        private const val NEAR_MS = 60_000L
        private const val FIRST_PICTURE_TIMEOUT_MS = 12_000L
        private const val PICTURE_TIMEOUT_MS = 8_000L
        private const val MAX_TIMEOUTS = 2
        private const val MAX_FAILURES = 3
        private const val MAX_CRASHES = 2
        private const val IDLE_RELEASE_MS = 45_000L
        /** A decoding error this soon after a picture was asked may come from reading it. */
        private const val SUSPECT_WINDOW_MS = 10_000L
        /** After this many such errors, this TV gets only the time (kept across restarts). */
        private const val MAX_SUSPECT_ERRORS = 2
        private const val PREFS = "rctv_seek_preview"
        private const val KEY_SUSPECT_ERRORS = "suspect_decoder_errors"

        /** Crashes of the helper while reading pictures, this app run. */
        private val crashes = AtomicInteger(0)

        @Volatile
        private var disabledOnDevice: Boolean? = null
    }
}
