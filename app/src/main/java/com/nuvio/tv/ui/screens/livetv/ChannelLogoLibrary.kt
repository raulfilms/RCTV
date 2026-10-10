package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Channel logos bundled with the app (US channels, for the Guide). They ship as one zip in the
 * app's assets, unpacked once into the app's files on first use; channels are matched to a
 * logo by name: "ESPN2 HD" -> espn-2, "The Weather Channel" -> weather-channel,
 * "FanDuel Sports Network Detroit" -> bally-sports-detroit, a call sign ("KTLA 5") -> that
 * station's logo, a local "ABC 7 Detroit" (sub-channel "7.1") -> the ABC logo.
 */
internal object ChannelLogoLibrary {

    private const val TAG = "ChannelLogoLibrary"
    private const val ASSET = "channel-logos-us.zip"
    /** Bump when the bundled zip changes, so it's unpacked again. */
    private const val VERSION = 1

    @Volatile private var directory: File? = null
    @Volatile private var keys: Set<String> = emptySet()

    /** Unpacks the bundled logos if needed. Slow the first time: call off the main thread. */
    fun ensureReady(context: Context): Boolean {
        if (directory != null) return true
        synchronized(this) {
            if (directory != null) return true
            return runCatching {
                val target = File(context.filesDir, "channel-logos/us")
                val marker = File(target, ".v$VERSION")
                if (!marker.exists()) {
                    target.deleteRecursively()
                    target.mkdirs()
                    context.assets.open(ASSET).use { input ->
                        ZipInputStream(BufferedInputStream(input)).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                // Flat names only: nothing can be written outside the folder.
                                val name = entry.name.substringAfterLast('/')
                                if (!entry.isDirectory && name.endsWith(".webp") && !name.startsWith(".")) {
                                    File(target, name).outputStream().use { zip.copyTo(it) }
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                    }
                    marker.createNewFile()
                }
                keys = target.list()
                    ?.filter { it.endsWith(".webp") }
                    ?.map { it.removeSuffix(".webp") }
                    ?.toSet()
                    .orEmpty()
                directory = target
                true
            }.getOrElse { error ->
                Log.w(TAG, "Bundled channel logos unavailable", error)
                false
            }
        }
    }

    /** A "file://" URI of the bundled logo for this channel, or null. Call after [ensureReady]. */
    fun logoUriFor(name: String, number: String?): String? {
        val dir = directory ?: return null
        val key = match(name, number) ?: return null
        return "file://" + File(dir, "$key.webp").absolutePath
    }

    private val noise = setOf("hd", "fhd", "uhd", "4k", "sd", "east", "west", "pacific", "us", "feed", "alternate", "alt", "hdtv")

    private val aliases = mapOf(
        "sho" to "showtime", "sho-2" to "showtime-2", "encore" to "starz-encore",
        "encore-action" to "starz-encore-action", "accn-espn" to "espn-accn",
        "cable-news-network" to "cnn", "golf" to "nbc-golf-channel", "golf-channel" to "nbc-golf-channel",
        "usa-network" to "usa", "science" to "discovery-science", "science-channel" to "discovery-science",
        "discovery-id" to "investigation-discovery", "id" to "investigation-discovery",
        "nicktoons" to "nick-toons", "nickteen" to "teen-nick", "teennick" to "teen-nick",
        "c-span" to "c-span-1", "cspan" to "c-span-1", "cspan-2" to "c-span-2", "cspan-3" to "c-span-3",
        "bloomberg" to "bloomberg-television", "bloomberg-business-television" to "bloomberg-television",
        "e" to "e-entertainment", "e-entertainment-television" to "e-entertainment",
        "fxm" to "fxm-movie-channel", "motortrend" to "motor-trend", "fs1" to "fox-sports-1",
        "fs2" to "fox-sports-2", "history" to "history-channel", "discovery" to "discovery-channel",
        "hallmark" to "hallmark-channel", "ion" to "ion-television", "cw" to "the-cw",
        "nfl-redzone" to "nfl-red-zone", "nfl-sunday-ticket-red-zone" to "nfl-red-zone",
        "mtv-music-television" to "mtv", "mtv-2-music-television" to "mtv-2", "espnu" to "espn-u",
        "msnbc" to "ms-now", "fox-news-channel" to "fox-news", "turner-classic-movies" to "tcm",
        "own" to "oprah-winfrey-network", "gsn" to "game-show-network",
        "cnn-headline-news" to "hln", "headline-news" to "hln", "fox-deportes" to "fox-sports-deportes",
        "home-shopping-network" to "hsn", "nbc-sport-network" to "nbc-sports"
    )

    /** Broadcast networks whose logo stands in for a local affiliate. */
    private val networks = setOf("abc", "cbs", "nbc", "fox", "pbs", "cw", "telemundo", "univision", "unimas")
    private val genericFirstWords = setOf("the", "tv", "news", "channel", "sports", "my", "home")
    private val callSign = Regex("""(?<![A-Za-z0-9])([KW][A-Z]{2,3})(?![A-Za-z0-9])""")
    private val separators = Regex("""[^a-z0-9]+""")
    private val letterDigit = Regex("""(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])""")

    /** For dark tiles: white or light versions first, then wide ("hz") ones. */
    private fun prefer(base: String): String? = listOf(
        "$base-light-hz", "$base-white-hz", "$base-hz", "$base-light", "$base-white", base
    ).firstOrNull { it in keys }

    private val accents = Regex("""\p{Mn}+""")

    private fun tokens(name: String): List<String> {
        // "Español" -> "espanol", like the logo file names.
        val plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(accents, "")
        val text = plain.lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace("+", " plus ")
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("""\([^)]*\)"""), " ")
        var result = text.split(separators).filter { it.isNotEmpty() }
        if (result.size > 1 && result[0] == "the") result = result.drop(1)
        // FanDuel Sports Network is the renamed Bally Sports regional network.
        if (result.take(3) == listOf("fanduel", "sports", "network")) result = listOf("bally", "sports") + result.drop(3)
        return result
    }

    private fun matchTokens(core: List<String>, isLocal: Boolean): String? {
        if (core.isEmpty()) return null
        val joined = core.joinToString("-")
        val tries = mutableListOf<String>()
        aliases[joined]?.let { tries += it }
        tries += joined
        tries += joined.replace("-and-", "-")
        for (extra in listOf("channel", "network", "tv")) {
            tries += if (core.last() == extra && core.size > 1) core.dropLast(1).joinToString("-") else "$joined-$extra"
        }
        // "CBS Detroit" -> cbs-news-detroit
        if (core.size >= 2) tries += core[0] + "-news-" + core.drop(1).joinToString("-")
        tries.forEach { candidate -> prefer(candidate)?.let { return it } }

        if (isLocal && core[0] in networks) prefer(core[0])?.let { return it }

        // Longest known start: "NBC Sports Boston" -> nbc-sports.
        for (count in core.size - 1 downTo 1) {
            if (count == 1 && (core[0].length < 3 || core[0] in genericFirstWords)) break
            val start = core.take(count).joinToString("-")
            prefer(aliases[start] ?: start)?.let { return it }
        }
        return null
    }

    private fun match(name: String, number: String?): String? {
        if (keys.isEmpty()) return null
        // A station's call sign picks its own logo.
        callSign.findAll(name).forEach { found ->
            val sign = found.groupValues[1].lowercase(Locale.ROOT)
            val best = keys.filter { sign in it.split('-') }.minByOrNull { it.length }
            if (best != null) return prefer(best) ?: best
        }
        val isLocal = number?.contains('.') == true
        val core = tokens(name).filter { it !in noise }
        matchTokens(core, isLocal)?.let { return it }
        // "ESPN2" -> espn-2, "QVC2" -> qvc-2.
        val split = core.flatMap { token -> token.split(letterDigit).filter { it.isNotEmpty() } }
        return if (split != core) matchTokens(split, isLocal) else null
    }
}
