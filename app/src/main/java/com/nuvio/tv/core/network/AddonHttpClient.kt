package com.nuvio.tv.core.network

import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

/**
 * RCTV (RCTV-AUD-021): the client for addon, IPTV (Xtream, Stalker, M3U, XMLTV) and subtitle-file
 * requests: [base]'s certificate and hostname checks, its interceptors and timeouts, and a cache of
 * its own. It used to accept any certificate and any host, and these requests carry debrid keys,
 * IPTV logins and portal tokens; a server with an invalid HTTPS certificate now fails like any
 * other TLS error (see [isTlsCertificateFailure]).
 */
internal object AddonHttpClient {

    /** A new cache directory: what the old one holds came over unchecked connections. */
    private const val CACHE_DIR = "addon_http_cache_v2"
    private const val LEGACY_CACHE_DIR = "addon_http_cache"
    private const val CACHE_BYTES = 50L * 1024 * 1024

    fun create(base: OkHttpClient, cacheRoot: File): OkHttpClient =
        base.newBuilder()
            .cache(Cache(File(cacheRoot, CACHE_DIR), CACHE_BYTES))
            .build()

    /** The old cache's files, removed off the calling thread (never read again). */
    fun dropLegacyCache(cacheRoot: File) {
        Thread({
            runCatching { File(cacheRoot, LEGACY_CACHE_DIR).takeIf { it.isDirectory }?.deleteRecursively() }
        }, "rctv-addon-cache-cleanup").apply { isDaemon = true }.start()
    }
}
