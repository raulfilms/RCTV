package com.nuvio.tv.core.player

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * RCTV (seek preview): a small picture of a movie or episode at a moment of it, for the window
 * over the timeline while it's moved with the remote (as YouTube TV). It runs in the helper
 * process the subtitle sync uses (":speech", see the manifest), never in the app's: if the TV's
 * frame reader fails or crashes on a file, only this process closes and the movie goes on.
 *
 * Only the nearest key frame is read (a small part of the file, never the whole movie), scaled
 * down to [KEY_WIDTH]×[KEY_HEIGHT] and sent back as a small JPEG. The file is kept open while the
 * timeline is being moved, and let go when the player stops asking.
 */
class SeekPreviewService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var messenger: Messenger? = null

    // Only touched on [thread].
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        val reader = HandlerThread("seek-preview", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        thread = reader
        val readerHandler = object : Handler(reader.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_FRAME -> answer(msg)
                    MSG_RELEASE -> releaseRetriever()
                }
            }
        }
        handler = readerHandler
        messenger = Messenger(readerHandler)
    }

    private fun answer(msg: Message) {
        val reply = msg.replyTo ?: return
        val data = msg.data
        val url = data.getString(KEY_URL)
        val positionMs = data.getLong(KEY_POSITION, -1L)
        val out = Bundle().apply { putLong(KEY_POSITION, positionMs) }
        if (url.isNullOrBlank() || positionMs < 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            out.putBoolean(KEY_UNSUPPORTED, true)
        } else {
            val headers = data.getBundle(KEY_HEADERS)?.let { bundle ->
                bundle.keySet().associateWith { bundle.getString(it).orEmpty() }
            }.orEmpty()
            val opened = try {
                open(url, headers)
            } catch (e: Exception) {
                // Only the error's kind: the address can carry a login.
                Log.w(TAG, "Couldn't open the file for previews: ${e.javaClass.simpleName}")
                releaseRetriever()
                null
            }
            if (opened == null) {
                out.putBoolean(KEY_UNSUPPORTED, true)
            } else {
                frameAt(opened, positionMs, data.getInt(KEY_WIDTH, DEFAULT_WIDTH), data.getInt(KEY_HEIGHT, DEFAULT_HEIGHT))
                    ?.let { out.putByteArray(KEY_IMAGE, it) }
            }
        }
        val answer = Message.obtain(null, MSG_RESULT).apply { this.data = out }
        try {
            reply.send(answer)
        } catch (e: Exception) {
            Log.w(TAG, "The player left before the preview")
        }
    }

    private fun open(url: String, headers: Map<String, String>): MediaMetadataRetriever {
        retriever?.takeIf { retrieverUrl == url }?.let { return it }
        releaseRetriever()
        val created = MediaMetadataRetriever()
        try {
            created.setDataSource(url, headers)
        } catch (e: Exception) {
            runCatching { created.release() }
            throw e
        }
        retriever = created
        retrieverUrl = url
        return created
    }

    private fun frameAt(source: MediaMetadataRetriever, positionMs: Long, width: Int, height: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        val bitmap: Bitmap = try {
            source.getScaledFrameAtTime(
                positionMs * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                width.coerceIn(64, MAX_SIDE),
                height.coerceIn(36, MAX_SIDE)
            )
        } catch (e: Exception) {
            Log.w(TAG, "No preview at that moment: ${e.javaClass.simpleName}")
            null
        } ?: return null
        return try {
            ByteArrayOutputStream(32 * 1024).use { bytes ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
                bytes.toByteArray()
            }
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun releaseRetriever() {
        val old = retriever ?: return
        retriever = null
        retrieverUrl = null
        runCatching { old.release() }
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onUnbind(intent: Intent?): Boolean {
        handler?.sendEmptyMessage(MSG_RELEASE)
        return false
    }

    override fun onDestroy() {
        handler?.sendEmptyMessage(MSG_RELEASE)
        thread?.quitSafely()
        super.onDestroy()
    }

    internal companion object {
        const val TAG = "SeekPreview"
        const val MSG_FRAME = 1
        const val MSG_RESULT = 2
        const val MSG_RELEASE = 3
        const val KEY_URL = "url"
        const val KEY_HEADERS = "headers"
        const val KEY_POSITION = "position"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_IMAGE = "image"
        const val KEY_UNSUPPORTED = "unsupported"
        const val DEFAULT_WIDTH = 320
        const val DEFAULT_HEIGHT = 180
        private const val MAX_SIDE = 640
        private const val JPEG_QUALITY = 78
    }
}
