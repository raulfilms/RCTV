package com.nuvio.tv.core.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Debug
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * RCTV: when the app closes with no error to show (the system closed it for memory, or it crashed in
 * native code, on a TV whose Android doesn't say why), what it was doing is still known: every few
 * seconds the app notes which screen is on and how its memory and the TV's stand, and whether the
 * system asked it to free memory. A session that ended while the app was on screen and not closed
 * on purpose is shown the next time it opens, like a crash.
 */
object SessionTrail {

    private const val FILE_NAME = "last_session.txt"
    private const val EVERY_MS = 4_000L
    private const val RUNNING = "running"
    private const val AWAY = "away"
    private const val CLOSED_ON_PURPOSE = "closed"

    @Volatile private var appContext: Context? = null
    @Volatile private var state = AWAY
    @Volatile private var screen = "start"
    @Volatile private var screenSinceMs = System.currentTimeMillis()
    @Volatile private var memoryAsks = ""
    @Volatile private var previous: String? = null
    private var startedActivities = 0

    // The screens this session went through, each with the most native memory seen on it ("home 210").
    private val path = ArrayList<String>()
    @Volatile private var screenPeakNativeMb = 0L
    // The images shown on the screen that's on (how many, how much memory), and the session's biggest.
    private var screenImages = 0
    private var screenImageBytes = 0L
    private val biggestImages = ArrayList<Pair<Long, String>>()
    /** Notes are written here, off the screen's thread. */
    private val writer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "rctv-session-trail").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    /** Starts noting (the last session's ending is read first). */
    fun install(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (appContext != null) return
        appContext = app
        previous = runCatching { readUnexpectedEnd(File(app.filesDir, FILE_NAME)) }.getOrNull()

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                state = RUNNING
                writeSoon()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0 && state == RUNNING) {
                    state = AWAY
                    writeSoon()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                val name = when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "MODERATE"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "LOW"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "CRITICAL"
                    else -> return
                }
                noteMemoryAsk(name)
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = noteMemoryAsk("LOW_MEMORY")

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        })

        runCatching {
            writer.scheduleWithFixedDelay({ if (state == RUNNING) write() }, EVERY_MS, EVERY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** The screen on now (its route, without what's in it). */
    fun setScreen(route: String?) {
        val name = route?.substringBefore('?')?.substringBefore('/')?.takeIf { it.isNotBlank() } ?: return
        if (name == screen) return
        synchronized(this) {
            path += "$screen ${maxOf(screenPeakNativeMb, nativeMb())}"
            while (path.size > PATH_KEPT) path.removeAt(0)
            screenPeakNativeMb = nativeMb()
            screenImages = 0
            screenImageBytes = 0L
        }
        screen = name
        screenSinceMs = System.currentTimeMillis()
        if (state == RUNNING) writeSoon()
    }

    /**
     * An image shown ([source]: its address; only its site and file name are kept): counted for the
     * screen that's on, and kept when it's one of the session's biggest.
     */
    @Synchronized
    fun noteImage(source: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val bytes = width.toLong() * height.toLong() * 4L
        screenImages++
        screenImageBytes += bytes
        if (biggestImages.size < BIGGEST_KEPT || bytes > biggestImages.last().first) {
            val name = shortSource(source)
            biggestImages.removeAll { it.second.startsWith("$name ") }
            biggestImages += bytes to "$name ${width}x$height (${bytes / MB} MB)"
            biggestImages.sortByDescending { it.first }
            while (biggestImages.size > BIGGEST_KEPT) biggestImages.removeAt(biggestImages.lastIndex)
        }
    }

    /** "a.espncdn.com …/ari.png": the site and the file, not the whole address. */
    private fun shortSource(source: String): String {
        val noQuery = source.substringBefore('?')
        val host = noQuery.substringAfter("://", "").substringBefore('/')
        val file = noQuery.substringAfterLast('/').take(40)
        return if (host.isBlank()) file.ifBlank { "image" } else "$host …/$file"
    }

    private fun nativeMb(): Long = runCatching { Debug.getNativeHeapAllocatedSize() / MB }.getOrDefault(0L)

    /** The app is being closed on purpose (exit, restart): not an unexpected close (written at once). */
    fun markClosedOnPurpose() {
        state = CLOSED_ON_PURPOSE
        write()
    }

    /** How the last session ended, once, when it ended with the app on screen and not on purpose. */
    fun takeUnexpectedEnd(): String? {
        val text = previous
        previous = null
        return text
    }

    private fun noteMemoryAsk(level: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        // The latest few, newest last.
        memoryAsks = (memoryAsks.split(", ").filter { it.isNotBlank() } + "$level $time").takeLast(4).joinToString(", ")
        if (state == RUNNING) writeSoon()
    }

    private fun writeSoon() {
        runCatching { writer.execute { write() } }
    }

    @Synchronized
    private fun write() {
        val context = appContext ?: return
        runCatching {
            val runtime = Runtime.getRuntime()
            val javaUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
            val javaMaxMb = runtime.maxMemory() / MB
            val nativeMb = Debug.getNativeHeapAllocatedSize() / MB
            if (nativeMb > screenPeakNativeMb) screenPeakNativeMb = nativeMb
            val device = ActivityManager.MemoryInfo().also { info ->
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
            }
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val onScreenFor = (System.currentTimeMillis() - screenSinceMs) / 1000
            val text = buildString {
                append("state=").append(state).append('\n')
                append("time=").append(time).append('\n')
                append("screen=").append(screen).append(" (").append(onScreenFor).append(" s)\n")
                append("app=Java ").append(javaUsedMb).append(" of ").append(javaMaxMb).append(" MB · native ").append(nativeMb).append(" MB\n")
                append("tv=").append(device.availMem / MB).append(" MB free of ").append(device.totalMem / MB)
                    .append(" MB (low: ").append(if (device.lowMemory) "yes" else "no").append(")\n")
                append("asks=").append(memoryAsks.ifBlank { "none" }).append('\n')
                append("path=").append((path + "$screen $nativeMb").joinToString(" → ")).append(" (native MB)\n")
                append("images=").append(screenImages).append(" on this screen (")
                    .append(screenImageBytes / MB).append(" MB)")
                if (biggestImages.isNotEmpty()) {
                    append(" · biggest: ").append(biggestImages.joinToString(", ") { it.second })
                }
                append('\n')
                append("device=Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER)
                    .append(' ').append(Build.MODEL).append('\n')
            }
            File(context.filesDir, FILE_NAME).writeText(text)
        }
    }

    /** The last session's notes as the crash dialog shows them, when it ended unexpectedly. */
    private fun readUnexpectedEnd(file: File): String? {
        if (!file.exists()) return null
        val values = file.readLines().mapNotNull { line ->
            val key = line.substringBefore('=', missingDelimiterValue = "")
            if (key.isBlank()) null else key to line.substringAfter('=')
        }.toMap()
        if (values["state"] != RUNNING) return null
        return buildString {
            append(values["time"].orEmpty()).append(" · system exit\n")
            append("reason: closed with no error (the system closed it, most likely for memory, or a native crash)\n")
            append("screen: ").append(values["screen"].orEmpty()).append('\n')
            append("app memory: ").append(values["app"].orEmpty()).append('\n')
            append("TV memory: ").append(values["tv"].orEmpty()).append('\n')
            append("asked to free memory: ").append(values["asks"].orEmpty()).append('\n')
            values["path"]?.let { append("screens: ").append(it).append('\n') }
            values["images"]?.let { append("images: ").append(it).append('\n') }
            append(values["device"].orEmpty())
        }
    }

    private const val MB = 1024L * 1024L
    private const val PATH_KEPT = 6
    private const val BIGGEST_KEPT = 3
}
