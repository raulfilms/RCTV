package com.nuvio.tv.core.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** What the phone page shows about the IMDb account linked to the profile. */
data class ImdbLinkStatus(
    val linked: Boolean,
    /** "Linked: name" or "Not linked". */
    val title: String,
    /** Its counts and when it last synced (null when it isn't linked). */
    val details: String?,
    /** What the last sync missed (its watchlist is private...), or null. */
    val problem: String?,
    val syncing: Boolean,
    /** The profile it's linked for. */
    val profile: String?
)

/** How a link, sync or unlink asked from the phone went. */
data class ImdbLinkReply(val ok: Boolean, val message: String, val status: ImdbLinkStatus)

/**
 * RCTV: the little page a phone opens from the QR in IMDb's settings, to link the profile's IMDb
 * account by pasting its link (copied on IMDb, in the app or the site) instead of typing it with the
 * remote; it syncs or unlinks it too. Only the profile's public link travels to the TV, never an
 * IMDb password. It runs only while IMDb's settings are open on the TV.
 */
class ImdbLinkServer(
    private val status: () -> ImdbLinkStatus,
    private val link: (String) -> ImdbLinkReply,
    private val sync: () -> ImdbLinkReply,
    private val unlink: () -> ImdbLinkReply,
    private val context: Context?,
    port: Int = 8094
) : NanoHTTPD(port) {

    /** RCTV (SEC-04): only the phone that opened the TV's address (its key) gets an answer. */
    val access = LanConfigAccess()

    override fun serve(session: IHTTPSession): Response = access.guard(session, this::route)

    private fun route(session: IHTTPSession): Response = try {
        when {
            session.method == Method.GET && session.uri == "/" ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", ImdbLinkWebPage.html(context))
            session.method == Method.GET && session.uri == "/api/status" -> json(statusJson(status()))
            session.method == Method.POST && session.uri == "/api/link" -> json(replyJson(link(inputOf(session))))
            session.method == Method.POST && session.uri == "/api/sync" -> json(replyJson(sync()))
            session.method == Method.POST && session.uri == "/api/unlink" -> json(replyJson(unlink()))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error")
    }

    private fun json(body: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString()).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun statusJson(status: ImdbLinkStatus): JSONObject = JSONObject()
        .put("linked", status.linked)
        .put("title", status.title)
        .put("details", status.details ?: JSONObject.NULL)
        .put("problem", status.problem ?: JSONObject.NULL)
        .put("syncing", status.syncing)
        .put("profile", status.profile ?: JSONObject.NULL)

    private fun replyJson(reply: ImdbLinkReply): JSONObject = JSONObject()
        .put("ok", reply.ok)
        .put("message", reply.message)
        .put("status", statusJson(reply.status))

    /** The link pasted on the phone ({"input": "..."}). */
    private fun inputOf(session: IHTTPSession): String {
        val body = readUtf8Body(session)
        return runCatching { JSONObject(body).optString("input") }.getOrDefault("").trim().take(MAX_INPUT)
    }

    private fun readUtf8Body(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (length <= 0 || length > MAX_BODY) return ""
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(buffer, 0, offset, StandardCharsets.UTF_8)
    }

    companion object {
        private const val MAX_BODY = 16 * 1024
        private const val MAX_INPUT = 2_000

        fun startOnAvailablePort(
            status: () -> ImdbLinkStatus,
            link: (String) -> ImdbLinkReply,
            sync: () -> ImdbLinkReply,
            unlink: () -> ImdbLinkReply,
            context: Context?,
            startPort: Int = 8094,
            maxAttempts: Int = 10
        ): ImdbLinkServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = ImdbLinkServer(status, link, sync, unlink, context, port)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
