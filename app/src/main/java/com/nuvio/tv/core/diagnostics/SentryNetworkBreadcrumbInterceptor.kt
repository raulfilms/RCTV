package com.nuvio.tv.core.diagnostics

import com.nuvio.tv.core.network.forLog
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class SentryNetworkBreadcrumbInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAtNs = System.nanoTime()
        // RCTV (bug reports): the app's calls that failed or took long go in its last moments, with
        // Sentry on or off (the site only).
        if (!Sentry.isEnabled()) {
            try {
                val response = chain.proceed(request)
                noteForBugReports(request.url, request.method, response.code, elapsedMs(startedAtNs), null)
                return response
            } catch (error: IOException) {
                noteForBugReports(request.url, request.method, null, elapsedMs(startedAtNs), error)
                throw error
            } catch (error: RuntimeException) {
                noteForBugReports(request.url, request.method, null, elapsedMs(startedAtNs), error)
                throw error
            }
        }

        try {
            val response = chain.proceed(request)
            record(
                url = request.url,
                method = request.method,
                statusCode = response.code,
                elapsedMs = elapsedMs(startedAtNs),
                error = null
            )
            return response
        } catch (error: IOException) {
            record(
                url = request.url,
                method = request.method,
                statusCode = null,
                elapsedMs = elapsedMs(startedAtNs),
                error = error
            )
            throw error
        } catch (error: RuntimeException) {
            record(
                url = request.url,
                method = request.method,
                statusCode = null,
                elapsedMs = elapsedMs(startedAtNs),
                error = error
            )
            throw error
        }
    }

    private fun noteForBugReports(url: HttpUrl, method: String, statusCode: Int?, elapsedMs: Long, error: Throwable?) {
        val failed = error != null || (statusCode != null && statusCode >= 400)
        if (!failed && elapsedMs < SLOW_MS) return
        val outcome = when {
            error != null -> "${error.javaClass.simpleName} after $elapsedMs ms"
            failed -> "HTTP $statusCode in $elapsedMs ms"
            else -> "slow: HTTP $statusCode in $elapsedMs ms"
        }
        com.nuvio.tv.core.bugreport.BugReportLog.event("net", "$method ${url.host} → $outcome")
    }

    private fun record(
        url: HttpUrl,
        method: String,
        statusCode: Int?,
        elapsedMs: Long,
        error: Throwable?
    ) {
        noteForBugReports(url, method, statusCode, elapsedMs, error)
        // RCTV (RCTV-AUD-011, 021): scheme and host only. Addon requests share this interceptor and
        // their paths carry the user's debrid keys and addon configuration; nothing of the path or
        // query is sent to the crash service.
        val breadcrumb = if (statusCode == null) {
            Breadcrumb.http(url.forLog(), method)
        } else {
            Breadcrumb.http(url.forLog(), method, statusCode)
        }
        breadcrumb.setCategory("http.client")
        breadcrumb.setLevel(levelFor(statusCode, error))
        breadcrumb.setData("host", url.host)
        breadcrumb.setData("elapsed_ms", elapsedMs)
        if (error != null) {
            breadcrumb.setData("error_type", error.javaClass.name)
            error.message?.let { breadcrumb.setData("error_message", it.take(240)) }
        }
        Sentry.addBreadcrumb(breadcrumb)
    }

    private fun levelFor(statusCode: Int?, error: Throwable?): SentryLevel {
        if (error != null) return SentryLevel.ERROR
        val code = statusCode ?: return SentryLevel.INFO
        return when {
            code >= 500 -> SentryLevel.ERROR
            code >= 400 -> SentryLevel.WARNING
            else -> SentryLevel.INFO
        }
    }

    private companion object {
        /** A call this slow goes in a bug report's log even when it worked. */
        const val SLOW_MS = 4_000L
    }

    private fun elapsedMs(startedAtNs: Long): Long {
        return (System.nanoTime() - startedAtNs) / 1_000_000L
    }
}
