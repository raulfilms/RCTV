package com.nuvio.tv.core.rctvcontrol

import android.content.Context
import com.nuvio.tv.core.security.LocalSecrets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV Control: what the owner set on the website for this TV's account, kept on the TV so it works
 * without a connection too. The website wins: the debrid keys, the addons and the IPTV login here
 * are used instead of the ones typed on the TV (on every profile). Kept sealed with the Android
 * Keystore ([LocalSecrets]); never logged.
 */
@Singleton
class RctvManagedServices @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _services = MutableStateFlow(readServices())
    /** The account's services (empty: none set on the website, or the TV isn't activated). */
    val services: StateFlow<RctvServices> = _services.asStateFlow()

    private val _publicUrls = MutableStateFlow(readUrls())
    /** The public files the owner publishes on the website (rows, streaming services, announcements, banners...). */
    val publicUrls: StateFlow<RctvPublicUrls> = _publicUrls.asStateFlow()

    fun save(services: RctvServices, urls: RctvPublicUrls) {
        if (services != _services.value) {
            prefs.edit().putString(KEY_SERVICES, LocalSecrets.seal(toJson(services), AAD)).apply()
            _services.value = services
        }
        if (urls != _publicUrls.value) {
            prefs.edit()
                .putString(KEY_ROW_LAYOUT, urls.rowLayout)
                .putString(KEY_STREAMING, urls.streamingServices)
                .putString(KEY_ANNOUNCEMENTS, urls.announcements)
                .putString(KEY_BANNERS, urls.banners)
                .putString(KEY_COLLECTIONS, urls.collections)
                .apply()
            _publicUrls.value = urls
        }
    }

    /** The TV was disconnected from its account: nothing of the account stays. */
    fun clear() {
        prefs.edit().clear().apply()
        _services.value = RctvServices()
        _publicUrls.value = RctvPublicUrls()
    }

    private fun readServices(): RctvServices {
        val stored = prefs.getString(KEY_SERVICES, null) ?: return RctvServices()
        val json = LocalSecrets.open(stored, AAD) ?: return RctvServices()
        return runCatching { RctvControlParser.servicesOf(JSONObject(json)) }.getOrDefault(RctvServices())
    }

    private fun readUrls() = RctvPublicUrls(
        rowLayout = prefs.getString(KEY_ROW_LAYOUT, null),
        streamingServices = prefs.getString(KEY_STREAMING, null),
        announcements = prefs.getString(KEY_ANNOUNCEMENTS, null),
        banners = prefs.getString(KEY_BANNERS, null),
        collections = prefs.getString(KEY_COLLECTIONS, null)
    )

    /** The same shape device-config sends, so it's read back with [RctvControlParser.servicesOf]. */
    private fun toJson(services: RctvServices): String {
        val json = JSONObject()
        val values = JSONObject()
        services.realDebrid?.let { values.put("realdebrid", it) }
        services.torbox?.let { values.put("torbox", it) }
        services.premiumize?.let { values.put("premiumize", it) }
        services.addons.forEach { (name, url) -> values.put(name, url) }
        if (services.addonList.isNotEmpty()) values.put("addon_list", org.json.JSONArray(services.addonList))
        json.put("services", values)
        services.iptv?.let { iptv ->
            json.put(
                "iptv",
                JSONObject()
                    .put("server", iptv.server)
                    .put("username", iptv.username)
                    .put("password", iptv.password)
                    .put("epg_url", iptv.epgUrl ?: JSONObject.NULL)
            )
        }
        return json.toString()
    }

    private companion object {
        const val PREFS = "rctv_control_services"
        const val KEY_SERVICES = "services"
        const val KEY_ROW_LAYOUT = "row_layout_url"
        const val KEY_STREAMING = "streaming_services_url"
        const val KEY_ANNOUNCEMENTS = "announcements_url"
        const val KEY_BANNERS = "banners_url"
        const val KEY_COLLECTIONS = "collections_url"
        const val AAD = "rctv-control:services"
    }
}
