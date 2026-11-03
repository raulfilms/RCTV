package com.nuvio.tv.core.util

/** RCTV (Phase 2): plain text scans that stand for a regular expression (see each), with no native copy of the text. */
object TextScan {

    /**
     * The text between `<marker>` and the next quote, at the first place where it isn't empty: what
     * `Regex("<marker>([^\"]+)\"").find(text)?.groupValues?.get(1)` gives, without a regex.
     */
    fun quotedValueAfter(text: String, marker: String): String? {
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) return null
            val start = at + marker.length
            val end = text.indexOf('"', start)
            if (end < 0) return null
            if (end > start) return text.substring(start, end)
            from = at + 1
        }
    }
}
