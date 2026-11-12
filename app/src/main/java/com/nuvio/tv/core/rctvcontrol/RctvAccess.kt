package com.nuvio.tv.core.rctvcontrol

import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * RCTV Control (the owner's website): what this TV's account lets it see.
 * Full: everything (movies, series, live TV and sports). Light: movies and series only.
 */
enum class RctvPlan { FULL, LIGHT }

/** What the TV knows about its account. Kept between launches, so a dropped connection changes nothing. */
data class RctvAccount(
    /** The plan saved on the account (Light or Full), before a trial. */
    val basePlan: RctvPlan,
    val master: Boolean = false,
    /** When the 24-hour Full trial ends; null without one. */
    val trialEndsAtMs: Long? = null
) {
    /** The plan right now: Full for Master, Full, or a trial still running; else Light. */
    fun planAt(nowMs: Long): RctvPlan =
        if (master || basePlan == RctvPlan.FULL || isTrialRunning(nowMs)) RctvPlan.FULL else RctvPlan.LIGHT

    fun isTrialRunning(nowMs: Long): Boolean = trialEndsAtMs != null && trialEndsAtMs > nowMs
}

/** The website's activation codes: 8 characters, without the ones that look alike (O, I, S, 0, 1, 5). */
object RctvCodes {
    const val LENGTH = 8
    const val ALPHABET = "ABCDEFGHJKLMNPQRTUVWXYZ2346789"

    /** What's typed, in capitals, only the code's characters, at most 8. */
    fun normalize(input: String): String = input.uppercase().filter { it in ALPHABET }.take(LENGTH)

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}

/** How an activation went. */
sealed interface RctvActivation {
    data class Activated(val token: String, val account: RctvAccount) : RctvActivation
    /** Wrong code, an inactive account (the server never says which). */
    data object WrongCode : RctvActivation
    /** Too many tries in a minute. */
    data object TooManyTries : RctvActivation
    /** No connection, or the server had a problem. */
    data object NoConnection : RctvActivation
}

/** The account as `device-config` sends it, and how often to ask again. */
data class RctvDeviceConfig(val account: RctvAccount, val checkEverySeconds: Int)

/**
 * Reads the website's answers. Only the plan fields: the rest of `device-config` (the account's
 * services and IPTV) is never read, kept or logged here.
 */
object RctvControlParser {
    /** The device token's shape (the server's own check). */
    private val TOKEN = Regex("^[A-Za-z0-9_-]{42,90}$")
    const val DEFAULT_CHECK_SECONDS = 300
    private const val MIN_CHECK_SECONDS = 60
    private const val MAX_CHECK_SECONDS = 3600

    fun planOf(value: String?): RctvPlan? = when (value?.trim()?.lowercase()) {
        "full" -> RctvPlan.FULL
        "light" -> RctvPlan.LIGHT
        else -> null
    }

    fun activation(status: Int, body: String?): RctvActivation {
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (status == 200 && json != null) {
            val token = json.optString("device_token", "")
            val plan = planOf(json.optStringOrNull("plan"))
            if (TOKEN.matches(token) && plan != null) {
                return RctvActivation.Activated(token, RctvAccount(basePlan = plan))
            }
            return RctvActivation.NoConnection
        }
        if (status == 429) return RctvActivation.TooManyTries
        val code = json?.optJSONObject("error")?.optStringOrNull("code")
        return if (status == 400 && code == "ACTIVATION_DENIED") RctvActivation.WrongCode else RctvActivation.NoConnection
    }

    /** null when the answer isn't a device config (the old website without a plan, a broken answer). */
    fun deviceConfig(body: String?): RctvDeviceConfig? {
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val plan = planOf(json.optStringOrNull("plan")) ?: return null
        // The website before v2 only sends "plan" (Full or Light, no trials).
        val base = planOf(json.optStringOrNull("base_plan")) ?: plan
        val trialEnds = json.optStringOrNull("trial_ends_at")?.let(::epochMsOf)
        val master = json.optBoolean("master", false)
        val every = json.optInt("heartbeat_seconds", DEFAULT_CHECK_SECONDS)
            .coerceIn(MIN_CHECK_SECONDS, MAX_CHECK_SECONDS)
        return RctvDeviceConfig(RctvAccount(basePlan = base, master = master, trialEndsAtMs = trialEnds), every)
    }

    /** "2026-11-12T03:00:00.123456+00:00" or "…Z" as milliseconds; null when it isn't a time. */
    fun epochMsOf(value: String): Long? =
        runCatching { OffsetDateTime.parse(value.trim().replace(' ', 'T')).toInstant().toEpochMilli() }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }
}
