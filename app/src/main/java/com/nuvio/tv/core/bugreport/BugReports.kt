package com.nuvio.tv.core.bugreport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV (owner): the bug report system. A button anywhere calls [open]; the list of problems shows
 * (the app hosts it over any screen, the player included); the person picks one and [send] does
 * the rest: the app gathers everything itself ([BugReportCollector]), keeps it on the TV
 * ([BugReportStore]) and hands it to each service connected ([BugReportSink]: Sentry now, Firebase
 * when it's added to [sinks]). What couldn't go yet goes later ([retryPending]: at the app's start,
 * and with every new report).
 */
@Singleton
class BugReports @Inject constructor(
    private val collector: BugReportCollector,
    private val store: BugReportStore,
    sentry: SentryBugReportSink,
    /** Starts with the reports (it follows the favorites and the history from then on). */
    @Suppress("unused") private val libraryProbe: BugReportLibraryProbe
) {
    /** Where reports go. RCTV: Firebase's sink joins this list when the app is connected to it. */
    private val sinks: List<BugReportSink> = listOf(sentry)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()

    private val _open = MutableStateFlow(false)
    /** The list of problems is on screen. */
    val isOpen: StateFlow<Boolean> = _open.asStateFlow()

    /** Shows the list of problems (from the bug button, wherever it is). */
    fun open() {
        BugReportLog.event("report", "bug report list opened")
        _open.value = true
    }

    fun close() {
        _open.value = false
    }

    /** How a report went: [sent] when a service took it now, else kept on the TV to send later. */
    data class Result(val reportId: String, val sent: Boolean)

    /**
     * The person picked [category]: the report is gathered, kept and sent on the app's own time,
     * so closing the list (or the screen) never stops it; the list waits for its [Result].
     */
    fun submit(category: BugReportCategory): Deferred<Result> = scope.async { send(category) }

    private suspend fun send(category: BugReportCategory): Result {
        val reportId = store.newReportId()
        BugReportLog.event("report", "bug report $reportId: ${category.key}")
        val report = try {
            collector.collect(category, reportId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Whatever failed to read, the report still goes with what it is.
            JSONObject()
                .put("schema", 1)
                .put("report_id", reportId)
                .put("created_at_ms", System.currentTimeMillis())
                .put("category", JSONObject().put("key", category.key).put("label", category.label))
                .put("collect_error", e.javaClass.simpleName)
        }
        withContext(Dispatchers.IO) { store.save(report) }
        val sent = withTimeoutOrNull(SEND_WAIT_MS) { sending.withLock { deliver(report) } } ?: false
        // The others waiting go along.
        scope.launch { retryPending() }
        return Result(reportId, sent)
    }

    /** Sends what's waiting on the TV to every service ready that hasn't taken it yet. */
    suspend fun retryPending() {
        sending.withLock {
            withContext(Dispatchers.IO) { store.pending() }.forEach { report -> deliver(report) }
        }
    }

    /** At the app's start: what's waiting goes a moment later (never in the way of the opening). */
    fun retryPendingSoon() {
        scope.launch {
            delay(START_DELAY_MS)
            runCatching { retryPending() }
        }
    }

    /** True when at least one service took it (now or before). */
    private suspend fun deliver(report: JSONObject): Boolean {
        val reportId = report.optString("report_id").ifBlank { return false }
        val names = sinks.map { it.name }
        val already = store.deliveredTo(report)
        var anyTook = already.isNotEmpty()
        for (sink in sinks) {
            if (sink.name in already || !sink.isReady()) continue
            val took = try {
                sink.send(report)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (took) {
                anyTook = true
                withContext(Dispatchers.IO) { store.markDelivered(reportId, sink.name, names) }
            }
        }
        return anyTook
    }

    private companion object {
        /** The person waits at most this long on "Sending…" (it goes on behind). */
        const val SEND_WAIT_MS = 15_000L
        const val START_DELAY_MS = 60_000L
    }
}
