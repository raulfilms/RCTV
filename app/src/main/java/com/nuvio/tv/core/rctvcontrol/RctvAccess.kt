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

/**
 * The account's services set on the website (they win over the TV's own): the debrid keys, the
 * addons (LatinoDebrid, Addon LATAM) and the IPTV login. Never logged ([toString] hides them).
 */
data class RctvServices(
    val realDebrid: String? = null,
    val torbox: String? = null,
    val premiumize: String? = null,
    /** Addon manifest addresses by the website's service ("latinodebrid", "addon_latam"). */
    val addons: Map<String, String> = emptyMap(),
    val iptv: RctvIptv? = null,
    /** The account's addons (Torrentio, AIOStreams...), in the website's order. They carry keys. */
    val addonList: List<String> = emptyList(),
    /**
     * RCTV: the server each of [addonList] is, by its link (the app's AddonRole keys: "aio_main",
     * "torrentio_rd", "latam", "other"...), as the website says; a link it doesn't say isn't here.
     */
    val addonRoles: Map<String, String> = emptyMap(),
    /** RCTV: the account's Seekr key (seekr.tv: the timeline's pictures while seeking). Never logged. */
    val seekr: String? = null
) {
    val isEmpty: Boolean get() =
        realDebrid == null && torbox == null && premiumize == null && addons.isEmpty() && addonList.isEmpty() && iptv == null && seekr == null

    /** Every addon the website sets, in its order: the account's list, then LatinoDebrid and Addon LATAM. */
    val managedAddonUrls: List<String> get() =
        (addonList + RctvControlParser.ADDON_SERVICES.mapNotNull { addons[it] }).distinct()

    override fun toString(): String =
        "RctvServices(debrid=${listOfNotNull(realDebrid?.let { "rd" }, torbox?.let { "tb" }, premiumize?.let { "pm" })}, addons=${addons.keys}, addonList=${addonList.size}, iptv=${iptv != null}, seekr=${seekr != null})"
}

/** The account's Xtream Codes login (only with Full). Never logged. */
data class RctvIptv(val server: String, val username: String, val password: String, val epgUrl: String? = null) {
    override fun toString(): String = "RctvIptv(hidden)"
}

/** A profile of the account, the same on every TV: its place (1 to 8, 1 the main one) and look. */
data class RctvRemoteProfile(
    val slot: Int,
    val name: String,
    val color: String = DEFAULT_COLOR,
    val avatar: String? = null,
    val avatarUrl: String? = null,
    val isKids: Boolean = false
) {
    companion object {
        const val DEFAULT_COLOR = "#1E88E5"
    }
}

/** The public files the owner publishes on the website (rows, streaming services, announcements, banners, collections). */
data class RctvPublicUrls(
    val rowLayout: String? = null,
    val streamingServices: String? = null,
    val announcements: String? = null,
    val banners: String? = null,
    val collections: String? = null
)

/** What the website said when something started playing. */
sealed interface RctvPlaybackStart {
    data class Allowed(val sessionId: String, val beatSeconds: Int) : RctvPlaybackStart
    /** "TOO_MANY_IPTV", "TOO_MANY_VOD" or "NO_IPTV_IN_PLAN". */
    data class Denied(val reason: String) : RctvPlaybackStart
    /** It couldn't be asked (no connection, not activated): it plays. */
    data object Unchecked : RctvPlaybackStart
}

/** The account as `device-config` sends it, and how often to ask again. */
data class RctvDeviceConfig(
    val account: RctvAccount,
    val checkEverySeconds: Int,
    val services: RctvServices = RctvServices(),
    /** The account's profiles; null from a website without them (the TV's profiles stay as they are). */
    val profiles: List<RctvRemoteProfile>? = null,
    val publicUrls: RctvPublicUrls = RctvPublicUrls()
)

/** Reads the website's answers. The account's services are read here but never logged. */
object RctvControlParser {
    /** The device token's shape (the server's own check). */
    private val TOKEN = Regex("^[A-Za-z0-9_-]{42,90}$")
    /** RCTV: a Seekr key's shape (letters, digits, "_", "-", "."). */
    private val SEEKR_KEY = Regex("^[A-Za-z0-9_.-]{8,200}$")
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
        return RctvDeviceConfig(
            account = RctvAccount(basePlan = base, master = master, trialEndsAtMs = trialEnds),
            checkEverySeconds = every,
            services = servicesOf(json),
            profiles = json.optJSONArray("profiles")?.let(::profilesOf),
            publicUrls = json.optJSONObject("public_urls")?.let { urls ->
                val announcements = urls.optStringOrNull("announcements")?.takeIf(::isHttps)
                RctvPublicUrls(
                    rowLayout = urls.optStringOrNull("row_layout")?.takeIf(::isHttps),
                    streamingServices = urls.optStringOrNull("streaming_services")?.takeIf(::isHttps),
                    announcements = announcements,
                    // A website from before banners doesn't send them: they sit next to the announcements.
                    banners = urls.optStringOrNull("banners")?.takeIf(::isHttps) ?: siblingOf(announcements, "banners.json"),
                    collections = urls.optStringOrNull("collections")?.takeIf(::isHttps) ?: siblingOf(announcements, "collections.json")
                )
            } ?: RctvPublicUrls()
        )
    }

    fun playbackStart(status: Int, body: String?): RctvPlaybackStart {
        if (status != 200) return RctvPlaybackStart.Unchecked
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return RctvPlaybackStart.Unchecked
        return if (json.optBoolean("allowed", false)) {
            val id = json.optStringOrNull("session_id") ?: return RctvPlaybackStart.Unchecked
            RctvPlaybackStart.Allowed(id, json.optInt("beat_seconds", 30).coerceIn(10, 120))
        } else {
            RctvPlaybackStart.Denied(json.optStringOrNull("reason") ?: "UNKNOWN")
        }
    }

    /** The website's services: debrid keys, addons (HTTPS only) and the Xtream login (only with Full). */
    fun servicesOf(json: JSONObject): RctvServices {
        val services = json.optJSONObject("services")
        fun key(name: String) = services?.optStringOrNull(name)?.trim()?.takeIf { it.length in 8..200 }
        val addons = buildMap {
            ADDON_SERVICES.forEach { name ->
                services?.optStringOrNull(name)?.trim()?.takeIf(::isHttps)?.let { put(name, it) }
            }
        }
        // The account's addons, in order (HTTPS only), and (RCTV) the server each one is, in the same order.
        val roles = services?.optJSONArray("addon_roles")
        val listed = services?.optJSONArray("addon_list")?.let { list ->
            (0 until list.length()).mapNotNull { i ->
                list.optString(i, "").trim().takeIf { url ->
                    url.startsWith("https://") && url.length <= MAX_ADDON_URL && url.none(Char::isWhitespace)
                }?.let { url -> url to roles?.optString(i, "")?.trim()?.takeIf { ROLE.matches(it) } }
            }.distinctBy { it.first }.take(MAX_ADDONS)
        }.orEmpty()
        val addonList = listed.map { it.first }
        val addonRoles = listed.mapNotNull { (url, role) -> role?.let { url to it } }.toMap()
        val iptv = json.optJSONObject("iptv")?.let { x ->
            val server = x.optStringOrNull("server")?.trim()?.trimEnd('/')
            val username = x.optStringOrNull("username")
            val password = x.optStringOrNull("password")
            // Many IPTV servers only have HTTP: an address the owner typed, used as it is.
            if (server != null && username != null && password != null &&
                (server.startsWith("https://") || server.startsWith("http://"))
            ) {
                RctvIptv(server, username, password, x.optStringOrNull("epg_url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") })
            } else {
                null
            }
        }
        return RctvServices(
            realDebrid = key("realdebrid"),
            torbox = key("torbox"),
            premiumize = key("premiumize"),
            addons = addons,
            iptv = iptv,
            addonList = addonList,
            addonRoles = addonRoles,
            // One word: it goes in a header to Seekr only.
            seekr = services?.optStringOrNull("seekr")?.trim()?.takeIf { SEEKR_KEY.matches(it) }
        )
    }

    fun profilesOf(array: org.json.JSONArray): List<RctvRemoteProfile> = buildList {
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val slot = p.optInt("slot", 0).takeIf { it in 1..8 } ?: continue
            val name = p.optStringOrNull("name")?.trim()?.take(60) ?: continue
            add(
                RctvRemoteProfile(
                    slot = slot,
                    name = name,
                    color = p.optStringOrNull("color")?.takeIf { COLOR.matches(it) } ?: RctvRemoteProfile.DEFAULT_COLOR,
                    avatar = p.optStringOrNull("avatar")?.takeUnless { it == "default" },
                    avatarUrl = p.optStringOrNull("avatar_url")?.takeIf(::isHttps),
                    isKids = slot != 1 && p.optBoolean("is_kids", false)
                )
            )
        }
    }.distinctBy { it.slot }.sortedBy { it.slot }

    private fun isHttps(value: String): Boolean = value.startsWith("https://") && value.length <= 500

    /** [name] in the same folder as [url] (".../rctv-public/announcements.json" -> ".../rctv-public/banners.json"). */
    private fun siblingOf(url: String?, name: String): String? {
        val folder = url?.substringBeforeLast('/', "")?.takeIf { it.startsWith("https://") } ?: return null
        return "$folder/$name"
    }

    /** The website's addon services. */
    val ADDON_SERVICES = listOf("latinodebrid", "addon_latam")
    const val MAX_ADDONS = 30
    private val ROLE = Regex("[a-z_]{2,20}")
    private const val MAX_ADDON_URL = 4000
    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")

    /** "2026-11-12T03:00:00.123456+00:00" or "…Z" as milliseconds; null when it isn't a time. */
    fun epochMsOf(value: String): Long? =
        runCatching { OffsetDateTime.parse(value.trim().replace(' ', 'T')).toInstant().toEpochMilli() }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }
}
