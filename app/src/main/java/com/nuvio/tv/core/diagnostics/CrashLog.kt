package com.nuvio.tv.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RCTV: when the app closes on an error, what went wrong is kept (a file in the app's own space),
 * and the next time it opens it's shown once, so it can be told and fixed without a computer.
 * When the system itself closed the app (it ran out of memory, froze, or crashed in native code),
 * there's no error to keep: Android says why the next time it opens, and that's shown instead.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 24_000
    private const val PREFS = "crash_log"
    private const val LAST_EXIT_KEY = "last_exit_seen_ms"
    private const val MAX_TRACE_CHARS = 6_000
    private const val REPORTED_FILE = "reported_crash.txt"
    private const val REPORTED_KEPT_MS = 7L * 24 * 60 * 60 * 1000
    /** RCTV: a helper process's crash (the subtitle sync's sound reader): its own error, kept. */
    private const val HELPER_FILE = "helper_crash.txt"
    private const val HELPER_SHOWN_KEY = "helper_crash_shown_ms"
    private const val HELPER_EXIT_KEY = "helper_exit_seen_ms"

    /** RCTV: what the helper process is doing now (no addresses), kept with its crash. */
    @Volatile
    var helperWork: String? = null

    /**
     * RCTV (bug reports): the last crash or unexpected close as the app showed it, kept a week so a
     * report ("App crashed") carries it ([forReports]).
     */
    fun keepForReports(context: Context, text: String) {
        runCatching { File(context.applicationContext.filesDir, REPORTED_FILE).writeText(text.take(MAX_CHARS)) }
    }

    /** The crash kept by [keepForReports] (and a helper's), when from the last week; null otherwise. */
    fun forReports(context: Context): String? {
        val shown = recent(File(context.applicationContext.filesDir, REPORTED_FILE))
        val helper = recent(File(context.applicationContext.filesDir, HELPER_FILE))
        return listOfNotNull(shown, helper?.let { "Helper process (the app kept going):\n$it" })
            .joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun recent(file: File): String? = runCatching {
        if (!file.exists()) return@runCatching null
        if (System.currentTimeMillis() - file.lastModified() > REPORTED_KEPT_MS) {
            file.delete()
            return@runCatching null
        }
        file.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * RCTV: in a helper process (":speech", the subtitle sync's sound reader and the timeline's
     * pictures), its crash is kept for bug reports only: it never closes the app (the app goes on
     * without it), so it's never shown as the app closing.
     */
    fun installForHelper(context: Context) {
        val appContext = context.applicationContext
        helperFilesDir = appContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            writeHelperError(thread.name, error, caught = false)
            previous?.uncaughtException(thread, error)
        }
    }

    @Volatile
    private var helperFilesDir: File? = null

    /**
     * RCTV: a serious error (out of memory...) the helper caught and went on after: kept like its
     * crash, so it's told and fixed too.
     */
    fun noteHelperError(error: Throwable) {
        writeHelperError(Thread.currentThread().name, error, caught = true)
    }

    private fun writeHelperError(threadName: String, error: Throwable, caught: Boolean) {
        val dir = helperFilesDir ?: return
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMb = runtime.maxMemory() / (1024 * 1024)
            val doing = helperWork?.let { "doing: $it\n" }.orEmpty()
            val what = if (caught) "caught, the helper went on" else "the helper closed"
            File(dir, HELPER_FILE).writeText(
                ("$time · $threadName\nprocess: subtitle sync helper ($what; the app kept going)\n" +
                    doing + "memory: $usedMb of $maxMb MB\n" + error.stackTraceToString()).take(MAX_CHARS)
            )
        }
    }

    /** Keeps every crash's error (then lets the app close as it would have). */
    fun install(context: Context) {
        val appContext = context.applicationContext
        // RCTV: and what the app was doing, for a close with no error to keep (see SessionTrail).
        runCatching { SessionTrail.install(appContext) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val file = File(appContext.filesDir, FILE_NAME)
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                // What it was first, in a few bytes: out of memory, the full trace may not fit.
                file.writeText("$time · ${thread.name}\n${error.javaClass.name}: ${error.message}\n")
                runCatching { file.writeText("$time · ${thread.name}\n${error.stackTraceToString()}".take(MAX_CHARS)) }
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
     * Why the system closed the app last time, once, when it wasn't a normal close (Android 11 and
     * newer keep it): out of memory, frozen (with where it was stuck), a native crash (with its
     * trace), or a crash this app's own log missed. Null when the last close was normal.
     */
    fun takeLastExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(LAST_EXIT_KEY, 0L)
            val manager = appContext.getSystemService(ActivityManager::class.java) ?: return@runCatching null
            val exits = manager.getHistoricalProcessExitReasons(appContext.packageName, 0, 10)
                .filter { it.timestamp > seen }
            if (exits.isEmpty()) return@runCatching null
            prefs.edit().putLong(LAST_EXIT_KEY, exits.maxOf { it.timestamp }).apply()
            // RCTV: only the app's own process closing is the app closing; a helper process's crash
            // (the subtitle sync's sound reader) leaves the app playing: kept for reports, not shown.
            val exit = exits.sortedByDescending { it.timestamp }
                .firstOrNull { it.isAbnormal() && (it.processName == null || it.processName == appContext.packageName) }
                ?: return@runCatching null
            describe(exit)
        }.getOrNull()
    }

    /**
     * RCTV: a helper process's error since last shown, once: its own error when it kept one (what it
     * was doing, its memory, where it failed), else what Android says of its close. The app itself
     * kept going. Null when there was none.
     */
    fun takeHelperProblem(context: Context): String? = runCatching {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = File(appContext.filesDir, HELPER_FILE)
        val shownAt = prefs.getLong(HELPER_SHOWN_KEY, 0L)
        if (file.exists() && file.lastModified() > shownAt) {
            prefs.edit().putLong(HELPER_SHOWN_KEY, file.lastModified()).apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) markHelperExitsSeen(appContext)
            return@runCatching file.readText().takeIf { it.isNotBlank() }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@runCatching null
        val seen = prefs.getLong(HELPER_EXIT_KEY, 0L)
        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return@runCatching null
        val exits = manager.getHistoricalProcessExitReasons(appContext.packageName, 0, 10)
            .filter { it.timestamp > seen && it.processName != null && it.processName != appContext.packageName }
        if (exits.isEmpty()) return@runCatching null
        prefs.edit().putLong(HELPER_EXIT_KEY, exits.maxOf { it.timestamp }).apply()
        exits.sortedByDescending { it.timestamp }.firstOrNull { it.isAbnormal() }?.let { describe(it) }
    }.getOrNull()

    private fun markHelperExitsSeen(appContext: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = appContext.getSystemService(ActivityManager::class.java) ?: return
            val latest = manager.getHistoricalProcessExitReasons(appContext.packageName, 0, 10)
                .filter { it.processName != appContext.packageName }
                .maxOfOrNull { it.timestamp } ?: return
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(HELPER_EXIT_KEY, latest).apply()
        }
    }

    private fun ApplicationExitInfo.isAbnormal(): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> true
        // Killed by a signal while in front: the low-memory killer on many TVs.
        ApplicationExitInfo.REASON_SIGNALED -> importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        else -> false
    }

    private fun describe(exit: ApplicationExitInfo): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR (app froze)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (system out of memory)"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED ${exit.status} (killed by the system)"
            else -> "reason ${exit.reason}"
        }
        val header = buildString {
            append("$time · system exit\n")
            append("reason: $reason\n")
            // RCTV: which part closed (the app itself, or its subtitle sync helper).
            exit.processName?.let { append("process: $it\n") }
            exit.description?.takeIf { it.isNotBlank() }?.let { append("description: $it\n") }
            append("importance: ${exit.importance} · pss: ${exit.pss / 1024} MB · rss: ${exit.rss / 1024} MB\n")
        }
        // A frozen app's trace: where its main thread was stuck (a native crash's is binary).
        val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) {
            runCatching {
                exit.traceInputStream?.bufferedReader()?.use { reader ->
                    val text = StringBuilder()
                    var inMain = false
                    var line = reader.readLine()
                    while (line != null && text.length < MAX_TRACE_CHARS) {
                        if (line.startsWith("\"main\"")) inMain = true
                        else if (inMain && line.isBlank()) break
                        if (inMain) text.append(line.trim()).append('\n')
                        line = reader.readLine()
                    }
                    text.toString()
                }
            }.getOrNull()
        } else {
            null
        }
        return header + trace.orEmpty()
    }

    /** A system exit's text (see [takeLastExit]): shown as it is, not summarized. */
    fun isSystemExit(text: String): Boolean = text.lineSequence().firstOrNull()?.endsWith("system exit") == true

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
