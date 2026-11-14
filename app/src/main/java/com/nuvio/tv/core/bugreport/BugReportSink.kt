package com.nuvio.tv.core.bugreport

import io.sentry.Attachment
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.protocol.SentryId
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV: a service bug reports go to (Sentry now; Firebase when the app is connected to it). Each
 * gets every report once ([BugReportStore] notes which took it).
 */
interface BugReportSink {
    /** Its name in the reports' notes ("sentry"). */
    val name: String

    /** Whether it can take reports now (connected and on). */
    fun isReady(): Boolean

    /** Sends [report]; true when the service took it. */
    suspend fun send(report: JSONObject): Boolean
}

/**
 * Sentry (when the build has its address and Sentry is on in Settings): one issue per kind of
 * problem ("Bug report: Movie won't play"), each report an event in it, searchable by its tags
 * (report_id, category, install_id, model, app version, plan, screen), the whole report attached
 * as a JSON file and its log as a text file. Sentry keeps them on the TV until it's online.
 */
@Singleton
class SentryBugReportSink @Inject constructor() : BugReportSink {

    override val name: String = "sentry"

    override fun isReady(): Boolean = Sentry.isEnabled()

    override suspend fun send(report: JSONObject): Boolean {
        if (!Sentry.isEnabled()) return false
        val category = report.optJSONObject("category")
        val label = category?.optString("label").orEmpty().ifBlank { "Other issue" }
        val key = category?.optString("key").orEmpty().ifBlank { "other" }
        val reportId = report.optString("report_id")
        val device = report.optJSONObject("device")
        val app = report.optJSONObject("app")
        val account = report.optJSONObject("account")
        val playback = report.optJSONObject("playback")
        val log = report.optString("log")
        val withoutLog = JSONObject(report.toString()).apply { remove("log") }
        val id = Sentry.captureMessage("Bug report: $label", SentryLevel.WARNING) { scope ->
            scope.setFingerprint(listOf("rctv-bug-report", key))
            scope.setTag("bug_report", "true")
            scope.setTag("report_id", reportId)
            scope.setTag("category", key)
            category?.optString("area")?.takeIf { it.isNotBlank() }?.let { scope.setTag("area", it) }
            account?.optString("install_id")?.takeIf { it.isNotBlank() }?.let { scope.setTag("install_id", it) }
            account?.optString("plan")?.takeIf { it.isNotBlank() }?.let { scope.setTag("plan", it) }
            account?.optString("account_name")?.takeIf { it.isNotBlank() }?.let { scope.setTag("account", it.take(200)) }
            account?.optString("account_id")?.takeIf { it.isNotBlank() }?.let { scope.setTag("account_id", it) }
            account?.optJSONObject("profile")?.optString("name")?.takeIf { it.isNotBlank() }
                ?.let { scope.setTag("profile", it) }
            device?.optString("model")?.takeIf { it.isNotBlank() }?.let { scope.setTag("device_model", it) }
            device?.optString("name")?.takeIf { it.isNotBlank() }?.let { scope.setTag("device_name", it) }
            app?.optString("version_name")?.takeIf { it.isNotBlank() }?.let { scope.setTag("app_version", it) }
            report.optJSONObject("screen")?.optString("now")?.takeIf { it.isNotBlank() }
                ?.let { scope.setTag("screen", it) }
            playback?.optJSONObject("content")?.let { content ->
                content.optString("contentType").takeIf { it.isNotBlank() }?.let { scope.setTag("content_type", it) }
                content.optString("title").takeIf { it.isNotBlank() }?.let { scope.setExtra("title", it) }
            }
            scope.addAttachment(
                Attachment(withoutLog.toString(2).toByteArray(), "bug-report-$reportId.json", "application/json")
            )
            if (log.isNotBlank()) {
                scope.addAttachment(Attachment(log.toByteArray(), "app-log-$reportId.txt", "text/plain"))
            }
        }
        return id != SentryId.EMPTY_ID
    }
}
