package com.nuvio.tv.core.diagnostics

import android.app.Application
import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.ui.screens.player.LivePlaybackUiPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * RCTV (owner): Firebase, when the app is connected to the owner's Firebase project (the build has
 * its google-services.json, from a GitHub secret; without it there is no Firebase and all of this
 * does nothing). Crashlytics gets the crashes (as Sentry does) and the bug reports
 * ([com.nuvio.tv.core.bugreport.FirebaseBugReportSink]); Analytics counts how the app is used: the
 * screens opened and what kind of thing starts playing (a movie, an episode, a live channel), never
 * a link, a server, an account, a login or a title. Both follow the crash reports switch in
 * Settings (off: nothing is sent).
 */
object FirebaseDiagnostics {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var appContext: Context? = null
    @Volatile private var enabled = false
    @Volatile private var lastScreen: String? = null

    /** Whether this build is connected to Firebase (its project's settings are in the app). */
    fun isConnected(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    /** Connected and on (the crash reports switch). */
    fun isActive(): Boolean = appContext != null && enabled

    /** Crashlytics, when connected and on; null otherwise. */
    fun crashlytics(): FirebaseCrashlytics? =
        if (isActive()) runCatching { FirebaseCrashlytics.getInstance() }.getOrNull() else null

    fun start(application: Application, settings: SentrySettingsDataStore) {
        if (appContext != null || !isConnected(application)) return
        appContext = application
        scope.launch {
            settings.enabled.distinctUntilChanged().collect { on -> applyEnabled(application, on) }
        }
    }

    private fun applyEnabled(application: Application, on: Boolean) {
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCrashlyticsCollectionEnabled(on)
                setCustomKey("app_flavor", BuildConfig.FLAVOR)
                setCustomKey("app_build_type", BuildConfig.BUILD_TYPE)
            }
        }
        runCatching { FirebaseAnalytics.getInstance(application).setAnalyticsCollectionEnabled(on) }
        enabled = on
    }

    /** A screen opened ([route] as the app names it; only its name is sent: "player", "live_tv"). */
    fun screen(route: String?) {
        val context = appContext ?: return
        val name = route?.substringBefore('?')?.substringBefore('/')?.takeIf { it.isNotBlank() } ?: return
        if (name == lastScreen) return
        lastScreen = name
        // Crashlytics: a crash says which screen was on.
        runCatching { FirebaseCrashlytics.getInstance().setCustomKey("screen", name) }
        log(context, FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, "MainActivity")
        })
    }

    /** Something started playing (its first picture): only what kind of thing it is. */
    fun playbackStarted(contentType: String?) {
        val context = appContext ?: return
        val kind = if (LivePlaybackUiPolicy.isLiveTvContentType(contentType)) {
            "live"
        } else {
            when (contentType?.lowercase(Locale.ROOT)) {
                "movie" -> "movie"
                "series", "tv", "episode" -> "episode"
                else -> "other"
            }
        }
        log(context, "playback_start", Bundle().apply { putString("content_kind", kind) })
    }

    /** A bug report was sent from the app (only its kind of problem: "movie_wont_play"). */
    fun bugReport(categoryKey: String) {
        val context = appContext ?: return
        log(context, "bug_report", Bundle().apply { putString("category", categoryKey.take(MAX_PARAM)) })
    }

    private fun log(context: Context, event: String, params: Bundle) {
        if (!enabled) return
        runCatching { FirebaseAnalytics.getInstance(context).logEvent(event, params) }
    }

    /** Firebase Analytics' longest text for an event's detail. */
    private const val MAX_PARAM = 100
}
