package com.nuvio.tv.core.subsync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * RCTV: asks [SpeechAnalysisService] (a process of its own) for a movie's sound in some stretches of
 * it. Null when it can't be read, takes too long, or the reading process crashed; after
 * [MAX_CRASHES] crashes it isn't asked again until the app restarts.
 */
internal object SpeechAnalysisClient {
    private const val TAG = "SubtitleAutoSync"
    private const val MAX_CRASHES = 2
    private val crashes = AtomicInteger(0)

    suspend fun analyze(
        context: Context,
        url: String,
        headers: Map<String, String>,
        windows: List<LongRange>,
        timeoutMs: Long
    ): List<SpeechWindow>? {
        if (windows.isEmpty() || crashes.get() >= MAX_CRASHES) return null
        val app = context.applicationContext
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) { ask(app, url, headers, windows) }
        }
    }

    private suspend fun ask(
        context: Context,
        url: String,
        headers: Map<String, String>,
        windows: List<LongRange>
    ): List<SpeechWindow>? = suspendCancellableCoroutine { continuation ->
        val done = AtomicBoolean(false)
        var connection: ServiceConnection? = null

        fun finish(result: List<SpeechWindow>?) {
            if (!done.compareAndSet(false, true)) return
            connection?.let { runCatching { context.unbindService(it) } }
            if (continuation.isActive) continuation.resume(result)
        }

        val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == SpeechAnalysisService.MSG_RESULT) finish(heardOf(message.data))
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val request = Message.obtain(null, SpeechAnalysisService.MSG_ANALYZE).apply {
                    replyTo = replies
                    data = Bundle().apply {
                        putString(SpeechAnalysisService.KEY_URL, url)
                        putBundle(SpeechAnalysisService.KEY_HEADERS, Bundle().apply {
                            headers.forEach { (key, value) -> putString(key, value) }
                        })
                        putLongArray(SpeechAnalysisService.KEY_STARTS, LongArray(windows.size) { windows[it].first })
                        putLongArray(SpeechAnalysisService.KEY_ENDS, LongArray(windows.size) { windows[it].last })
                    }
                }
                try {
                    Messenger(service).send(request)
                } catch (e: Exception) {
                    finish(null)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The reading process closed while reading: a crash there, not in the app.
                if (!done.get()) {
                    val count = crashes.incrementAndGet()
                    Log.w(TAG, "The sound reader closed while reading ($count)")
                }
                finish(null)
            }

            override fun onBindingDied(name: ComponentName?) = finish(null)

            override fun onNullBinding(name: ComponentName?) = finish(null)
        }
        val bound = try {
            context.bindService(Intent(context, SpeechAnalysisService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            false
        }
        if (!bound) finish(null)
        continuation.invokeOnCancellation { finish(null) }
    }

    private fun heardOf(data: Bundle?): List<SpeechWindow>? {
        if (data == null || !data.containsKey(SpeechAnalysisService.KEY_COUNT)) return null
        val count = data.getInt(SpeechAnalysisService.KEY_COUNT)
        val starts = data.getLongArray(SpeechAnalysisService.KEY_STARTS) ?: return null
        if (starts.size != count) return null
        return (0 until count).map { i ->
            SpeechWindow(starts[i], data.getFloatArray(SpeechAnalysisService.KEY_SPEECH + i) ?: FloatArray(0))
        }
    }
}
