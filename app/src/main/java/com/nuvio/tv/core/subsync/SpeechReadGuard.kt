package com.nuvio.tv.core.subsync

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * RCTV: reading the movie's sound from its file (for the subtitle sync) uses the TV's own file reader
 * and audio decoder, which can crash on some files and close the whole app (the Mi Box did, twice,
 * with Che: Part One, while the subtitles synced). A mark is kept while the sound is read: still there
 * the next time, that read closed the app, and the file isn't read on this TV again (the sync still
 * hears the sound the player decodes). One read at a time.
 */
internal object SpeechReadGuard {
    private const val TAG = "SubtitleAutoSync"
    private const val PREFS = "subtitle_sync_reader"
    private const val KEY_READING = "reading"
    private const val KEY_CLOSED_APP = "closed_app"

    private val readingNow = AtomicInteger(0)

    /** [read] when the file may be read on this TV; null when it may not, or another read is on. */
    fun <T> reading(context: Context, read: () -> T?): T? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!readingNow.compareAndSet(0, 1)) return null
        try {
            if (prefs.getBoolean(KEY_READING, false)) {
                // The last read never ended: it closed the app.
                Log.w(TAG, "Reading the file's sound closed the app before: not on this TV any more")
                prefs.edit().putBoolean(KEY_CLOSED_APP, true).putBoolean(KEY_READING, false).commit()
            }
            if (prefs.getBoolean(KEY_CLOSED_APP, false)) return null
            prefs.edit().putBoolean(KEY_READING, true).commit()
            com.nuvio.tv.core.diagnostics.SessionTrail.noteReadingSound(true)
            try {
                return read()
            } finally {
                prefs.edit().putBoolean(KEY_READING, false).commit()
                com.nuvio.tv.core.diagnostics.SessionTrail.noteReadingSound(false)
            }
        } finally {
            readingNow.set(0)
        }
    }
}
