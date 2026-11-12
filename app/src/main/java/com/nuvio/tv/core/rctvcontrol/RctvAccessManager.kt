package com.nuvio.tv.core.rctvcontrol

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.security.LocalSecrets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the app shows: the activation code page, or the app with its plan's menus. */
data class RctvAccessState(
    /** No code entered on this TV yet (or the website disconnected it): the code page shows. */
    val needsActivation: Boolean,
    val plan: RctvPlan,
    val account: RctvAccount? = null
)

/**
 * RCTV Control: this TV's account on the owner's website. The TV activates once with the account's
 * 8-character code; then, while the app is open, it asks for its plan (on opening and every 5
 * minutes, or when a trial ends). Without a connection the last plan it got stays.
 *
 * The device token is kept sealed with the Android Keystore ([LocalSecrets]), sent only to the
 * website's address over HTTPS (never on a redirect) and never logged.
 */
@Singleton
class RctvAccessManager @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient
) {
    private val baseUrl: String? = BuildConfig.RCTV_CONTROL_URL.trim().trimEnd('/')
        .takeIf { it.startsWith("https://") }

    /** False when this build has no website address: the TV works as before (Full, no code). */
    val isEnabled: Boolean get() = baseUrl != null

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Never follows a redirect: the device token only ever goes to the website's own address.
    // Nothing it answers is cached on disk (device-config also carries the account's services).
    private val client = okHttpClient.newBuilder()
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val checking = Mutex()

    private val _state = MutableStateFlow(stateNow())
    val state: StateFlow<RctvAccessState> = _state.asStateFlow()

    /** Enters the account's code. On success the app opens with the account's plan. */
    suspend fun activate(rawCode: String): RctvActivation {
        val url = baseUrl ?: return RctvActivation.NoConnection
        val code = RctvCodes.normalize(rawCode)
        if (!RctvCodes.isValid(code)) return RctvActivation.WrongCode
        val result = withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("code", code)
                .put("device_name", deviceName())
                .put("app_version", BuildConfig.VERSION_NAME.take(40))
                .toString()
            val request = Request.Builder()
                .url("$url/activate")
                .post(body.toRequestBody(JSON))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    RctvControlParser.activation(response.code, response.body?.string())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Activation failed: ${e.javaClass.simpleName}")
                RctvActivation.NoConnection
            }
        }
        if (result is RctvActivation.Activated) {
            saveToken(result.token)
            saveAccount(result.account)
            publish()
            // The plan's details (a trial, Master) right away.
            check()
        }
        return result
    }

    /**
     * Keeps the plan current while the app is open (run it from the screen's lifecycle): asks the
     * website now, then every few minutes, and again the moment a trial ends.
     */
    suspend fun keepCurrent() {
        while (true) {
            val seconds = check()
            delay(seconds * 1000L)
        }
    }

    /** Asks the website once; returns how many seconds to wait before asking again. */
    suspend fun check(): Long = checking.withLock {
        publish()
        val url = baseUrl ?: return@withLock RctvControlParser.DEFAULT_CHECK_SECONDS.toLong()
        val token = readToken() ?: return@withLock RctvControlParser.DEFAULT_CHECK_SECONDS.toLong()
        val outcome = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$url/device-config")
                .header("Authorization", "Bearer $token")
                .post(JSONObject().put("app_version", BuildConfig.VERSION_NAME.take(40)).toString().toRequestBody(JSON))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> Outcome.Disconnected
                        response.isSuccessful -> RctvControlParser.deviceConfig(response.body?.string())
                            ?.let { Outcome.Got(it) } ?: Outcome.Failed
                        else -> Outcome.Failed
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Plan check failed: ${e.javaClass.simpleName}")
                Outcome.Failed
            }
        }
        when (outcome) {
            is Outcome.Got -> {
                saveAccount(outcome.config.account)
                publish()
                nextWaitSeconds(outcome.config.checkEverySeconds.toLong(), outcome.config.account)
            }
            // The website disconnected this TV or turned the account off: the code page again.
            Outcome.Disconnected -> {
                forget()
                RctvControlParser.DEFAULT_CHECK_SECONDS.toLong()
            }
            // No connection: the last plan stays; asked again sooner.
            Outcome.Failed -> nextWaitSeconds(RETRY_SECONDS, readAccount())
        }
    }

    /** Forgets this TV's activation (the code page shows again). */
    fun forget() {
        prefs.edit().clear().apply()
        publish()
    }

    private fun nextWaitSeconds(every: Long, account: RctvAccount?): Long {
        val trialEnds = account?.trialEndsAtMs ?: return every
        val untilEnd = (trialEnds - System.currentTimeMillis()) / 1000 + 1
        return if (untilEnd in 1 until every) untilEnd else every
    }

    private fun publish() {
        _state.value = stateNow()
    }

    private fun stateNow(): RctvAccessState {
        if (!isEnabled) return RctvAccessState(needsActivation = false, plan = RctvPlan.FULL)
        if (!prefs.contains(KEY_TOKEN)) return RctvAccessState(needsActivation = true, plan = RctvPlan.FULL)
        val account = readAccount()
        // Activated, plan not known yet (a moment after the code): Light until the website answers.
        val plan = account?.planAt(System.currentTimeMillis()) ?: RctvPlan.LIGHT
        return RctvAccessState(needsActivation = false, plan = plan, account = account)
    }

    private fun readToken(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        val token = LocalSecrets.open(stored, TOKEN_AAD)
        if (token.isNullOrBlank()) {
            // Its key is gone (the app's data was moved): enter the code again.
            forget()
            return null
        }
        return token
    }

    private fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, LocalSecrets.seal(token, TOKEN_AAD)).apply()
    }

    private fun readAccount(): RctvAccount? {
        val plan = RctvControlParser.planOf(prefs.getString(KEY_PLAN, null)) ?: return null
        val trialEnds = prefs.getLong(KEY_TRIAL_ENDS, 0L).takeIf { it > 0L }
        return RctvAccount(basePlan = plan, master = prefs.getBoolean(KEY_MASTER, false), trialEndsAtMs = trialEnds)
    }

    private fun saveAccount(account: RctvAccount) {
        prefs.edit()
            .putString(KEY_PLAN, if (account.basePlan == RctvPlan.FULL) "full" else "light")
            .putBoolean(KEY_MASTER, account.master)
            .putLong(KEY_TRIAL_ENDS, account.trialEndsAtMs ?: 0L)
            .apply()
    }

    /** The TV's name as Android has it ("Living Room"), else its model. */
    private fun deviceName(): String {
        val named = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return (named?.takeIf { it.isNotBlank() } ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV")
            .trim().take(100)
    }

    private sealed interface Outcome {
        data class Got(val config: RctvDeviceConfig) : Outcome
        data object Disconnected : Outcome
        data object Failed : Outcome
    }

    private companion object {
        const val TAG = "RctvAccess"
        const val PREFS = "rctv_control"
        const val KEY_TOKEN = "device_token"
        const val KEY_PLAN = "base_plan"
        const val KEY_MASTER = "master"
        const val KEY_TRIAL_ENDS = "trial_ends_at"
        const val TOKEN_AAD = "rctv-control:device-token"
        const val RETRY_SECONDS = 60L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
