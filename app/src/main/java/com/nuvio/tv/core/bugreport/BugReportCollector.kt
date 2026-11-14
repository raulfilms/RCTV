package com.nuvio.tv.core.bugreport

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.diagnostics.CrashLog
import com.nuvio.tv.core.diagnostics.SessionTrail
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.rctvcontrol.RctvAccessManager
import com.nuvio.tv.core.smartscraper.ConnectionMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV (owner): everything a bug report says, gathered by the app itself the moment the person picks
 * what went wrong: the app and the TV (model, Android, screen, memory, storage), the connection
 * (Wi-Fi or cable, its speed, the last speed test), the account's plan and the profile, the screen
 * on and the ones before, what played or plays (the player's own details: title, IDs, episode,
 * source's site and kind, picture, sound, subtitles, errors, stalls, settings), the app's last
 * moments ([BugReportLog]), the last crash or close and the app's own system log.
 *
 * Never in it: an address beyond its site, the IPTV login, a debrid or API key, the website's
 * device token, a password. Free text is cleaned ([BugReportRedactor]); the player's details keep
 * only the source's site and fingerprints of its address.
 */
@Singleton
class BugReportCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val access: RctvAccessManager,
    private val profiles: ProfileManager,
    private val connection: ConnectionMonitor,
    private val store: BugReportStore
) {

    /** The report for [category] ([reportId]: the one the person sees). */
    suspend fun collect(category: BugReportCategory, reportId: String): JSONObject {
        // The screens' details first, on the screen's thread (the player is read there).
        val probes = try {
            withContext(Dispatchers.Main.immediate) { BugReportProbes.readAll() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            JSONObject().apply {
                put("schema", SCHEMA)
                put("report_id", reportId)
                put("created_at", isoTime(now))
                put("created_at_ms", now)
                put("category", JSONObject().apply {
                    put("key", category.key)
                    put("label", category.label)
                    put("area", category.area.name.lowercase(Locale.US))
                })
                put("app", section { app() })
                put("device", section { device() })
                put("network", section { network() })
                put("account", section { account() })
                put("screen", section { screen() })
                probes.forEach { (name, json) -> put(name, json) }
                put("events", JSONArray(BugReportLog.snapshot()))
                section { crashes() }.takeIf { it.length() > 0 }?.let { put("crashes", it) }
                put("log", readOwnLog())
            }
        }
    }

    private fun app() = JSONObject().apply {
        put("application_id", BuildConfig.APPLICATION_ID)
        put("version_name", BuildConfig.VERSION_NAME)
        put("version_code", BuildConfig.VERSION_CODE)
        put("build_type", BuildConfig.BUILD_TYPE)
        put("flavor", BuildConfig.FLAVOR)
        put("language", Locale.getDefault().toLanguageTag())
        put("time_zone", TimeZone.getDefault().id)
        put("running_for_s", (SystemClock.uptimeMillis() - Process.getStartUptimeMillis()) / 1000)
        val runtime = Runtime.getRuntime()
        put("java_memory_mb", (runtime.totalMemory() - runtime.freeMemory()) / MB)
        put("java_memory_max_mb", runtime.maxMemory() / MB)
        put("native_memory_mb", Debug.getNativeHeapAllocatedSize() / MB)
    }

    private fun device() = JSONObject().apply {
        val named = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        named?.takeIf { it.isNotBlank() }?.let { put("name", it.take(100)) }
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("android", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        val metrics = context.resources.displayMetrics
        put("screen", "${metrics.widthPixels}x${metrics.heightPixels} @${metrics.densityDpi}dpi")
        runCatching {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.mode?.let { put("display_mode", "${it.physicalWidth}x${it.physicalHeight} ${"%.2f".format(Locale.US, it.refreshRate)} Hz") }
        }
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(it)
        }
        put("ram_total_mb", memory.totalMem / MB)
        put("ram_free_mb", memory.availMem / MB)
        put("ram_low", memory.lowMemory)
        put("storage_free_mb", context.filesDir.usableSpace / MB)
    }

    private fun network() = JSONObject().apply {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager?.let { m -> runCatching { m.getNetworkCapabilities(m.activeNetwork) }.getOrNull() }
        if (caps == null) {
            put("connected", false)
        } else {
            put("connected", true)
            put("type", when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            })
            put("vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            put("internet_checked", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            put("link_down_mbps", caps.linkDownstreamBandwidthKbps / 1000)
        }
        connection.lastTestedMbps()?.let { put("last_speed_test_mbps", "%.1f".format(Locale.US, it)) }
    }

    private fun account() = JSONObject().apply {
        // Not the website's token or code: this install's own number, to tell one TV's reports apart.
        put("install_id", store.installId())
        val state = access.state.value
        put("activated", !state.needsActivation)
        put("plan", state.plan.name)
        state.account?.let { account ->
            put("master", account.master)
            put("trial", account.isTrialRunning(System.currentTimeMillis()))
            // Whose it is, as the website names the account and this TV.
            account.label?.let { label ->
                label.accountName?.let { put("account_name", it) }
                label.ownerName?.takeIf { it.isNotBlank() }?.let { put("owner_name", it) }
                label.accountId?.let { put("account_id", it) }
                label.deviceId?.let { put("website_device_id", it) }
                label.deviceName?.let { put("website_device_name", it) }
            }
        }
        put("website_last_check", access.lastCheck)
        profiles.activeProfile?.let { profile ->
            put("profile", JSONObject().apply {
                put("slot", profile.id)
                put("name", profile.name.take(40))
                put("kids", profile.isKids)
            })
        }
    }

    private fun screen() = JSONObject().apply {
        put("now", SessionTrail.screenName())
        // How the app stands right now: screens and their memory, what plays, the memory asks.
        SessionTrail.currentNotes()?.let { put("notes", BugReportRedactor.redact(it)) }
    }

    private fun crashes() = JSONObject().apply {
        CrashLog.forReports(context)?.let { put("last", BugReportRedactor.redact(it).take(CRASH_MAX)) }
    }

    /**
     * The app's own system log (Android lets an app read its own lines), the last [LOG_LINES]
     * lines, each cleaned. Empty when the TV doesn't allow it.
     */
    private suspend fun readOwnLog(): String = withTimeoutOrNull(LOG_TIMEOUT_MS) {
        try {
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "time", "-t", LOG_LINES.toString(), "--pid=${Process.myPid()}"
            ).redirectErrorStream(true).start()
            val text = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (text.length > LOG_MAX_CHARS) break
                    text.append(BugReportRedactor.redact(line).take(LOG_LINE_MAX)).append('\n')
                }
            }
            // Its output is read to the end: the dump is over (it never waits for more).
            runCatching { process.destroy() }
            text.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    } ?: ""

    /** A section that failed to read is left out, never the whole report. */
    private inline fun section(read: () -> JSONObject): JSONObject =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JSONObject().put("unreadable", e.javaClass.simpleName)
        }

    private fun isoTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(ms))

    private companion object {
        const val SCHEMA = 1
        const val MB = 1024L * 1024L
        const val CRASH_MAX = 24_000
        const val LOG_LINES = 600
        const val LOG_MAX_CHARS = 120_000
        const val LOG_LINE_MAX = 400
        const val LOG_TIMEOUT_MS = 4_000L
    }
}
