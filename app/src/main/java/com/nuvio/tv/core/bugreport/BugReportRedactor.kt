package com.nuvio.tv.core.bugreport

/**
 * RCTV: what a bug report carries as free text (the app's log, a crash, the session notes) is
 * cleaned first: no address keeps more than its site (an IPTV or debrid address carries the login
 * or a key in its path or query), no login, password, key or token stays, no e-mail address.
 */
object BugReportRedactor {

    private const val HIDDEN = "[redacted]"

    /** scheme://[user:pass@]host[:port]/path?query#part → scheme://host[:port]/… */
    private val URL = Regex("""\b([a-zA-Z][a-zA-Z0-9+.\-]{1,15})://(?:[^\s/@"'<>]+@)?([^\s/?#"'<>,;]+)([^\s"'<>]*)""")

    /** key=value or key: value for the names a secret goes under. */
    private val SECRET_PAIR = Regex(
        """(?i)\b(access_token|refresh_token|id_token|token|api[_-]?key|apikey|client_secret|secret|password|passwd|pass|pwd|username|user|login|auth|authorization|proxy-authorization|cookie|set-cookie|session|sessionid|sid|signature|sig|key|mac|serial|device_token)\b(\s*[=:]\s*)("[^"]*"|[^\s&,;"'}\]]+)"""
    )

    private val BEARER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/\-]+=*""")

    /** An Xtream address's path without its scheme: /live/<user>/<pass>/… */
    private val XTREAM_PATH = Regex("""/(live|movie|series|timeshift|timeshifts)/[^/\s"']+/[^/\s"']+/""", RegexOption.IGNORE_CASE)

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    /** A long run of letters AND digits (a key, a token, a hash): never a class or method name. */
    private val OPAQUE = Regex("""\b(?=[A-Za-z0-9_\-]*\d)(?=[A-Za-z0-9_\-]*[A-Za-z])[A-Za-z0-9_\-]{32,}\b""")

    /** A MAC address (the TV's network card). */
    private val MAC = Regex("""\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\b""")

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out = URL.replace(text) { match ->
            val scheme = match.groupValues[1]
            val host = match.groupValues[2]
            val rest = match.groupValues[3]
            "$scheme://$host" + if (rest.isNotEmpty()) "/…" else ""
        }
        out = XTREAM_PATH.replace(out) { "/${it.groupValues[1]}/$HIDDEN/$HIDDEN/" }
        out = BEARER.replace(out) { "${it.groupValues[1]} $HIDDEN" }
        out = SECRET_PAIR.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$HIDDEN" }
        out = EMAIL.replace(out, "[email]")
        out = MAC.replace(out, "[mac]")
        out = OPAQUE.replace(out, HIDDEN)
        return out
    }
}
