package com.nuvio.tv.core.rctvcontrol

/** RCTV Control: comparing addon addresses and putting the website's addons first on the TV. */
object RctvAddonOrder {
    /** The same addon address, with or without "/manifest.json" (keys in the path compared exactly). */
    fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /** [installed] with the website's addons ([managed], in its order) first and the rest after, as they were. */
    fun managedFirst(installed: List<String>, managed: List<String>): List<String> {
        val first = managed.mapNotNull { url -> installed.firstOrNull { same(it, url) } }.distinct()
        return first + installed.filter { it !in first }
    }

    private fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val query = trimmed.substringAfter('?', "")
        val path = trimmed.substringBefore('?').removeSuffix("/manifest.json").trimEnd('/')
        // Keys can be in the path: compared exactly (no change of case).
        return if (query.isEmpty()) path else "$path?$query"
    }
}
