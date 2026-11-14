package com.nuvio.tv.core.bugreport

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV: the bug reports kept on the TV (the app's own space) until every service they go to took
 * them ([BugReportSink]): a report made offline, or before a service is connected, is sent later.
 * At most [KEPT] wait (the oldest go first). Each also notes which services took it, so none gets
 * it twice.
 */
@Singleton
class BugReportStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    init {
        // What played last stays known after the app is closed and opened again.
        BugReportProbes.useFile(File(context.filesDir, LAST_FILE))
    }

    /** This install's own number (not the website's token or code): tells one TV's reports apart. */
    fun installId(): String {
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    /** A short number the person can read out ("RC-7K3QX2"). */
    fun newReportId(): String = "RC-" + (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    @Synchronized
    fun save(report: JSONObject) {
        val id = report.optString("report_id").ifBlank { return }
        runCatching {
            File(dir, "$id.json").writeText(report.toString())
            val files = pendingFiles()
            if (files.size > KEPT) files.take(files.size - KEPT).forEach { it.delete() }
        }
    }

    /** Waiting reports, oldest first. */
    @Synchronized
    fun pending(): List<JSONObject> = pendingFiles().mapNotNull { file ->
        runCatching { JSONObject(file.readText()) }.getOrElse {
            file.delete()
            null
        }
    }

    /** [sink] took [reportId]; the report goes when every one in [allSinks] took it. */
    @Synchronized
    fun markDelivered(reportId: String, sink: String, allSinks: Collection<String>) {
        val file = File(dir, "$reportId.json")
        val report = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val delivered = deliveredTo(report) + sink
        if (delivered.containsAll(allSinks)) {
            file.delete()
        } else {
            report.put(DELIVERED, JSONArray(delivered.toList()))
            runCatching { file.writeText(report.toString()) }
        }
    }

    fun deliveredTo(report: JSONObject): Set<String> {
        val list = report.optJSONArray(DELIVERED) ?: return emptySet()
        return (0 until list.length()).mapNotNull { list.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    private fun pendingFiles(): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()

    private companion object {
        const val DIR = "bug_reports"
        const val PREFS = "rctv_bug_reports"
        const val KEY_INSTALL_ID = "install_id"
        const val DELIVERED = "_delivered_to"
        const val KEPT = 30
        const val LAST_FILE = "bug_report_last.json"
        /** Without the ones that look alike (O/0, I/1, S/5). */
        const val ALPHABET = "ABCDEFGHJKLMNPQRTUVWXYZ2346789"
    }
}
