package com.nuvio.tv.core.bugreport

import android.content.Context
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.nuvio.tv.core.diagnostics.FirebaseDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV (owner): Firebase Crashlytics (when the app is connected to Firebase and crash reports are on
 * in Settings): each report is a non-fatal issue, one issue per kind of problem ("Bug report: Movie
 * won't play"), with the same details Sentry's has as its keys (report_id, category, install_id,
 * model, app version, plan, screen) and the end of its log. Crashlytics keeps it on the TV and
 * sends it itself (non-fatal issues go the next time the app opens). The report was already
 * cleaned of links and logins ([BugReportRedactor]).
 */
@Singleton
class FirebaseBugReportSink @Inject constructor(
    @ApplicationContext private val context: Context
) : BugReportSink {

    override val name: String = "firebase"

    /** Whether this build has Firebase at all (without it, reports never wait for it). */
    val isConnected: Boolean by lazy { FirebaseDiagnostics.isConnected(context) }

    override fun isReady(): Boolean = FirebaseDiagnostics.isActive()

    override suspend fun send(report: JSONObject): Boolean {
        val crashlytics = FirebaseDiagnostics.crashlytics() ?: return false
        val category = report.optJSONObject("category")
        val label = category?.optString("label").orEmpty().ifBlank { "Other issue" }
        val key = category?.optString("key").orEmpty().ifBlank { "other" }
        val reportId = report.optString("report_id")
        val device = report.optJSONObject("device")
        val app = report.optJSONObject("app")
        val account = report.optJSONObject("account")
        val content = report.optJSONObject("playback")?.optJSONObject("content")

        // The keys stay on Crashlytics until the next report: named for the last report.
        val keys = CustomKeysAndValues.Builder().apply {
            fun put(name: String, value: String?) {
                value?.takeIf { it.isNotBlank() }?.let { putString("bug_$name", it.take(MAX_VALUE)) }
            }
            put("report_id", reportId)
            put("category", key)
            put("area", category?.optString("area"))
            put("install_id", account?.optString("install_id"))
            put("plan", account?.optString("plan"))
            put("account", account?.optString("account_name"))
            put("account_id", account?.optString("account_id"))
            put("profile", account?.optJSONObject("profile")?.optString("name"))
            put("device_model", device?.optString("model"))
            put("device_name", device?.optString("name"))
            put("app_version", app?.optString("version_name"))
            put("screen", report.optJSONObject("screen")?.optString("now"))
            put("content_type", content?.optString("contentType"))
            put("title", content?.optString("title"))
        }.build()
        crashlytics.setCustomKeys(keys)
        // The end of the report's log, in the issue's logs.
        crashlytics.log("Bug report $reportId: $label")
        report.optString("log").lines().filter { it.isNotBlank() }.takeLast(LOG_LINES).forEach { line ->
            crashlytics.log(line.take(MAX_LINE))
        }
        crashlytics.recordException(BugReportIssue(label, key))
        FirebaseDiagnostics.bugReport(key)
        return true
    }

    private companion object {
        const val LOG_LINES = 120
        const val MAX_LINE = 400
        const val MAX_VALUE = 200
    }
}

/**
 * A bug report as Crashlytics shows it (not a real error): its own issue per kind of problem, which
 * Crashlytics tells apart by where an error comes from.
 */
class BugReportIssue(label: String, key: String) : Exception("Bug report: $label") {
    init {
        val method = key.replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "other" }
        stackTrace = arrayOf(StackTraceElement("RctvBugReport", method, "BugReport.kt", 1))
    }
}
