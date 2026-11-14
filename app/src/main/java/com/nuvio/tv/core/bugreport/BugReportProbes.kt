package com.nuvio.tv.core.bugreport

import org.json.JSONObject

/**
 * RCTV: what a screen knows that a bug report needs (the player: what plays, from where, its
 * picture, sound, subtitles, errors and stalls). A screen registers its probe while it's open; the
 * report asks each probe on the screen's thread (the player is read there). When the screen closes,
 * its last answer is kept ([keepLast]), so a report made right after still says what happened.
 */
object BugReportProbes {

    private class Probe(val owner: Any, val read: () -> JSONObject?)

    private val probes = LinkedHashMap<String, Probe>()
    private val kept = LinkedHashMap<String, JSONObject>()

    /**
     * RCTV: where the last answers are kept on the TV, so a report made after the app was closed
     * and opened again still says what played last ([BugReportStore] sets it).
     */
    @Volatile private var keptFile: java.io.File? = null
    private val fileLock = Any()
    private var keptLoaded = false

    fun useFile(file: java.io.File) {
        keptFile = file
        // Read off the screen's thread.
        kotlin.concurrent.thread(name = "bug-report-last", isDaemon = true) { synchronized(this) { loadKeptOnce() } }
    }

    private fun loadKeptOnce() {
        if (keptLoaded) return
        keptLoaded = true
        val file = keptFile ?: return
        runCatching {
            if (!file.exists()) return
            val saved = JSONObject(file.readText())
            saved.keys().forEach { name -> saved.optJSONObject(name)?.let { if (name !in kept) kept[name] = it } }
        }
    }

    private fun saveKept() {
        val file = keptFile ?: return
        val text = JSONObject().apply { kept.forEach { (name, json) -> put(name, json) } }.toString()
        // Written off the screen's thread (the player closes on it).
        kotlin.concurrent.thread(name = "bug-report-last", isDaemon = true) {
            synchronized(fileLock) { runCatching { file.writeText(text) } }
        }
    }

    /** [owner]: who registered it (a newer screen of the same kind replaces it). */
    fun register(name: String, owner: Any, read: () -> JSONObject?) {
        synchronized(this) { probes[name] = Probe(owner, read) }
    }

    /** The screen closes: its last answer is kept, the probe goes. Call on the screen's thread. */
    fun keepLast(name: String, owner: Any) {
        val probe = synchronized(this) { probes[name]?.takeIf { it.owner === owner } } ?: return
        val last = runCatching { probe.read() }.getOrNull()
        synchronized(this) {
            if (probes[name]?.owner === owner) probes.remove(name)
            if (last != null) {
                loadKeptOnce()
                last.put("closed_at_ms", System.currentTimeMillis())
                kept[name] = last
                saveKept()
            }
        }
    }

    /** Each probe's answer now (read on the screen's thread), else its last kept one. */
    fun readAll(): Map<String, JSONObject> {
        val (open, closed) = synchronized(this) {
            loadKeptOnce()
            probes.toMap() to kept.toMap()
        }
        val out = LinkedHashMap<String, JSONObject>()
        closed.forEach { (name, json) -> out[name] = JSONObject(json.toString()).put("state", "closed") }
        open.forEach { (name, probe) ->
            runCatching { probe.read() }.getOrNull()?.let { out[name] = it.put("state", "open") }
        }
        return out
    }
}
