package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal object PlayerPlaybackNetworking {
    /**
     * Client for playback, prefetch, probes and subtitles: the system's certificate and hostname
     * checks, with no way around them. RCTV (RCTV-AUD-002): a TLS error used to resend the request
     * through a client that trusted any certificate and any host, which anyone on the network path
     * could trigger; a server with an untrusted certificate now fails like any other TLS error.
     * Credential headers stay on the host they were sent to when a request is redirected
     * ([PlaybackRedirectCredentials], RCTV-AUD-009).
     */
    internal val playbackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .eventListenerFactory(PlaybackConnectionEvents)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addNetworkInterceptor(PlaybackRedirectCredentials.offHostGuard)
            .build()
    }

    /**
     * A client for a stream's own [defaultHeaders]: they only go to the stream's host (RCTV-AUD-009);
     * Authorization is kept on that host even across a port change (WebDAV behind a proxy). With
     * [streamUrl], a request of its own to another host (an HLS/DASH segment or a sidecar subtitle
     * on a CDN) is sent without the stream's credentials.
     */
    fun createHttpClient(defaultHeaders: Map<String, String> = emptyMap(), streamUrl: HttpUrl? = null): OkHttpClient {
        val builder = playbackHttpClient.newBuilder()
        PlaybackRedirectCredentials.streamHeaderScope(defaultHeaders, streamUrl)?.let { builder.addNetworkInterceptor(it) }
        return builder
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
    }

    @UnstableApi
    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        streamUrl: HttpUrl? = null
    ): DataSource.Factory {
        val client = createHttpClient(defaultHeaders, streamUrl)
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            if (defaultHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
        return LoggingDataSourceFactory(httpFactory, "HTTP")
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        streamUrl: HttpUrl? = null
    ): DataSource.Factory {
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders, streamUrl))
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }
}
