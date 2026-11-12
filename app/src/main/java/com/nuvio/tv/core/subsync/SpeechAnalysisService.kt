package com.nuvio.tv.core.subsync

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * RCTV: reads a movie's sound from its file for the subtitle sync, in a process of its own
 * (":speech", see the manifest). On Android 9 (the Mi Box) the TV's own file reader and audio
 * decoders crashed on some movies and closed the whole app; here a crash closes only this process,
 * and the player just gets no answer. One read at a time; it stops when the player leaves.
 */
class SpeechAnalysisService : Service() {

    private var thread: HandlerThread? = null
    private var messenger: Messenger? = null

    @Volatile
    private var cancelled = false

    override fun onCreate() {
        super.onCreate()
        val reader = HandlerThread("speech-reader").also { it.start() }
        thread = reader
        messenger = Messenger(object : Handler(reader.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != MSG_ANALYZE) return
                val reply = msg.replyTo ?: return
                val data = msg.data
                val url = data.getString(KEY_URL)
                val starts = data.getLongArray(KEY_STARTS)
                val ends = data.getLongArray(KEY_ENDS)
                val heard = if (url == null || starts == null || ends == null || starts.size != ends.size) {
                    null
                } else {
                    val headers = data.getBundle(KEY_HEADERS)?.let { bundle ->
                        bundle.keySet().associateWith { bundle.getString(it).orEmpty() }
                    }.orEmpty()
                    cancelled = false
                    try {
                        SpeechActivityAnalyzer.analyze(
                            url = url,
                            headers = headers,
                            windowsMs = starts.indices.map { starts[it]..ends[it] },
                            anyDecoder = true
                        ) { cancelled }
                    } catch (e: Exception) {
                        Log.w(TAG, "Couldn't read the sound: ${e.javaClass.simpleName}")
                        null
                    }
                }
                val out = Message.obtain(null, MSG_RESULT)
                out.data = Bundle().apply {
                    if (heard != null) {
                        putInt(KEY_COUNT, heard.size)
                        putLongArray(KEY_STARTS, LongArray(heard.size) { heard[it].startMs })
                        heard.forEachIndexed { i, window -> putFloatArray(KEY_SPEECH + i, window.speech) }
                    }
                }
                try {
                    reply.send(out)
                } catch (e: Exception) {
                    Log.w(TAG, "The player left before the answer")
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onUnbind(intent: Intent?): Boolean {
        cancelled = true
        return false
    }

    override fun onDestroy() {
        cancelled = true
        thread?.quitSafely()
        super.onDestroy()
    }

    internal companion object {
        const val TAG = "SubtitleAutoSync"
        const val MSG_ANALYZE = 1
        const val MSG_RESULT = 2
        const val KEY_URL = "url"
        const val KEY_HEADERS = "headers"
        const val KEY_STARTS = "starts"
        const val KEY_ENDS = "ends"
        const val KEY_COUNT = "count"
        const val KEY_SPEECH = "speech_"

        /** The process this service runs in (the manifest's android:process). */
        const val PROCESS_SUFFIX = ":speech"
    }
}
