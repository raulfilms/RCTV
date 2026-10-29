package com.nuvio.tv.core.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** One server on the phone page: set or not, and the addon's name and site when set (never its link). */
data class AddonRoleSlot(val key: String, val label: String, val addonName: String?, val host: String?)

data class AddonRolesStatus(val slots: List<AddonRoleSlot>, val plugins: List<String>)

data class AddonRolesReply(val ok: Boolean, val message: String, val status: AddonRolesStatus)

/**
 * RCTV: the phone page opened from the QR in Settings → Add-ons, to set up each server (AIO Stream
 * Main, IMAX, Extended, Dubbed; Torrentio Real-Debrid, TorBox, Premiumize; LatinoDebrid; Addon
 * LATAM) by pasting its addon link, and the plugins by their repository link. The links stay on the
 * TV; the page only ever shows a server's addon name and site. It runs only while the QR is shown.
 */
class AddonRolesServer(
    private val status: () -> AddonRolesStatus,
    private val save: (role: String, url: String) -> AddonRolesReply,
    private val clear: (role: String) -> AddonRolesReply,
    private val addPlugin: (url: String) -> AddonRolesReply,
    private val context: Context?,
    port: Int = 8104
) : NanoHTTPD(port) {

    /** Only the phone that opened the TV's address (its key) gets an answer. */
    val access = LanConfigAccess()

    override fun serve(session: IHTTPSession): Response = access.guard(session, this::route)

    private fun route(session: IHTTPSession): Response = try {
        when {
            session.method == Method.GET && session.uri == "/" ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", AddonRolesWebPage.html(context))
            session.method == Method.GET && session.uri == "/api/status" -> json(statusJson(status()))
            session.method == Method.POST && session.uri == "/api/save" -> {
                val body = bodyOf(session)
                json(replyJson(save(body.optString("role"), body.optString("url").trim().take(MAX_INPUT))))
            }
            session.method == Method.POST && session.uri == "/api/clear" -> json(replyJson(clear(bodyOf(session).optString("role"))))
            session.method == Method.POST && session.uri == "/api/plugin" ->
                json(replyJson(addPlugin(bodyOf(session).optString("url").trim().take(MAX_INPUT))))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error")
    }

    private fun json(body: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString()).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun statusJson(status: AddonRolesStatus): JSONObject = JSONObject()
        .put(
            "slots",
            JSONArray().apply {
                status.slots.forEach { slot ->
                    put(
                        JSONObject()
                            .put("key", slot.key)
                            .put("label", slot.label)
                            .put("name", slot.addonName ?: JSONObject.NULL)
                            .put("host", slot.host ?: JSONObject.NULL)
                    )
                }
            }
        )
        .put("plugins", JSONArray(status.plugins))

    private fun replyJson(reply: AddonRolesReply): JSONObject = JSONObject()
        .put("ok", reply.ok)
        .put("message", reply.message)
        .put("status", statusJson(reply.status))

    private fun bodyOf(session: IHTTPSession): JSONObject {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return JSONObject()
        if (length <= 0 || length > MAX_BODY) return JSONObject()
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return runCatching { JSONObject(String(buffer, 0, offset, StandardCharsets.UTF_8)) }.getOrDefault(JSONObject())
    }

    companion object {
        private const val MAX_BODY = 16 * 1024
        private const val MAX_INPUT = 4_000

        fun startOnAvailablePort(
            status: () -> AddonRolesStatus,
            save: (String, String) -> AddonRolesReply,
            clear: (String) -> AddonRolesReply,
            addPlugin: (String) -> AddonRolesReply,
            context: Context?,
            startPort: Int = 8104,
            maxAttempts: Int = 10
        ): AddonRolesServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = AddonRolesServer(status, save, clear, addPlugin, context, port)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
