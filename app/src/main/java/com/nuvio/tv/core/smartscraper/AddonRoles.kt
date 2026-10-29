package com.nuvio.tv.core.smartscraper

import java.util.Locale

/**
 * RCTV: the servers the person sets up from the phone (Settings → Add-ons → QR), each its own addon:
 * with its link given, the app knows which installed addon is which, whatever it's called, and
 * reads, ranks and asks it as that server (never shown).
 */
enum class AddonRole(
    val key: String,
    val label: String,
    val family: ProviderFamily,
    val variant: ProviderVariant = ProviderVariant.NONE,
    val debrid: DebridService? = null
) {
    AIO_MAIN("aio_main", "AIO Stream Main", ProviderFamily.AIOSTREAMS, ProviderVariant.MAIN),
    AIO_IMAX("aio_imax", "AIO Stream IMAX", ProviderFamily.AIOSTREAMS, ProviderVariant.IMAX),
    AIO_EXTENDED("aio_extended", "AIO Stream Extended", ProviderFamily.AIOSTREAMS, ProviderVariant.EXTENDED),
    AIO_DUBBED("aio_dubbed", "AIO Stream Dubbed", ProviderFamily.AIOSTREAMS, ProviderVariant.DUBBED),
    TORRENTIO_RD("torrentio_rd", "Torrentio Real-Debrid", ProviderFamily.TORRENTIO, debrid = DebridService.REAL_DEBRID),
    TORRENTIO_TB("torrentio_tb", "Torrentio TorBox", ProviderFamily.TORRENTIO, debrid = DebridService.TORBOX),
    TORRENTIO_PM("torrentio_pm", "Torrentio Premiumize", ProviderFamily.TORRENTIO, debrid = DebridService.PREMIUMIZE),
    LATINODEBRID("latinodebrid", "LatinoDebrid", ProviderFamily.LATINODEBRID),
    LATAM("latam", "Addon LATAM", ProviderFamily.LATAM);

    /** [provider] as this server: its family, and its setup and debrid service when the role says them. */
    fun applyTo(provider: ProviderIdentity): ProviderIdentity = provider.copy(
        family = family,
        variant = if (variant != ProviderVariant.NONE) variant else provider.variant,
        debrid = debrid ?: provider.debrid
    )

    companion object {
        fun ofKey(key: String?): AddonRole? = entries.firstOrNull { it.key == key?.trim() }
    }
}

/** RCTV: which installed addon (by its name in the app) is which server; kept up to date by the app. */
object AddonRoles {
    @Volatile private var byAddonName: Map<String, AddonRole> = emptyMap()

    fun roleOf(addonName: String?): AddonRole? = addonName?.let { byAddonName[it] }

    fun update(roles: Map<String, AddonRole>) {
        byAddonName = roles
    }

    /** An addon link as the app keeps it: no "stremio://", no "/manifest.json", no trailing "/". */
    fun normalizeUrl(input: String): String? {
        var url = input.trim()
        if (url.startsWith("stremio://", ignoreCase = true)) url = "https://" + url.substring("stremio://".length)
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
        url = url.trimEnd('/')
        if (url.endsWith("/manifest.json", ignoreCase = true)) url = url.dropLast("/manifest.json".length)
        return url.trimEnd('/').takeIf { it.length > "https://".length }
    }

    /** Two links are the same addon. */
    fun sameAddon(a: String?, b: String?): Boolean {
        val x = a?.let(::normalizeUrl)?.lowercase(Locale.ROOT) ?: return false
        val y = b?.let(::normalizeUrl)?.lowercase(Locale.ROOT) ?: return false
        return x == y
    }
}
