package com.nuvio.tv.core.bugreport

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * RCTV: the app's last moments, for a bug report: which screens were opened, what played and how
 * it went (started, stalled, failed and why). Kept in memory only (the last [KEPT] lines), each
 * line cleaned as it's written ([BugReportRedactor]): never an address, a login or a key.
 */
object BugReportLog {

    private const val KEPT = 300
    private const val LINE_MAX = 300

    private val lines = ArrayDeque<String>(KEPT)
    private val time = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    private var lastText: String? = null
    private var repeats = 0

    /**
     * One moment: [area] says where ("screen", "player", "net", "streams"…). The same moment again
     * and again (a site failing on every image) stays one line with how many times ("×12").
     */
    fun event(area: String, text: String) {
        val what = "[$area] ${BugReportRedactor.redact(text).take(LINE_MAX)}"
        val now = time.get()!!.format(Date())
        synchronized(lines) {
            if (what == lastText && lines.isNotEmpty()) {
                repeats++
                lines.removeLast()
                lines.addLast("$now $what (×${repeats + 1})")
                return
            }
            lastText = what
            repeats = 0
            if (lines.size >= KEPT) lines.removeFirst()
            lines.addLast("$now $what")
        }
    }

    /** The lines kept, oldest first. */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}
