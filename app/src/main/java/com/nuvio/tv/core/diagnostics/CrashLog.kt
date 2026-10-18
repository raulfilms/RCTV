package com.nuvio.tv.core.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RCTV: when the app closes on an error, what went wrong is kept (a file in the app's own space),
 * and the next time it opens it's shown once, so it can be told and fixed without a computer.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 24_000

    /** Keeps every crash's error (then lets the app close as it would have). */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "$time · ${thread.name}\n${error.stackTraceToString()}".take(MAX_CHARS)
                File(appContext.filesDir, FILE_NAME).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash's error, once (it's forgotten as it's read); null when there was none. */
    fun take(context: Context): String? = runCatching {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return@runCatching null
        val text = file.readText()
        file.delete()
        text.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * The error short enough to read on a TV: what it was and its causes, each with the first
     * lines of where it happened (the app's own first).
     */
    fun summary(trace: String, maxLines: Int = 26): String {
        val out = ArrayList<String>()
        var frames = 0
        var ownFrames = 0
        for (raw in trace.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val isFrame = line.startsWith("at ")
            when {
                line.startsWith("...") -> Unit
                !isFrame -> {
                    out += line
                    frames = 0
                    ownFrames = 0
                }
                frames < 2 -> {
                    out += line
                    frames++
                    if (line.contains("com.nuvio")) ownFrames++
                }
                line.contains("com.nuvio") && ownFrames < 4 -> {
                    out += line
                    ownFrames++
                }
            }
            if (out.size >= maxLines) break
        }
        return out.joinToString("\n")
    }
}
