package com.nuvio.tv.ui.util

import com.nuvio.tv.domain.model.Stream
import java.util.Locale

/**
 * RCTV: the app never says where something comes from — not the addon that found a stream
 * (AIOStreams, Torrentio...), not the service that plays it (Real-Debrid, Premiumize, TorBox...),
 * not the IPTV provider. Only what it is: a movie's or a show's name, a season and an episode, a
 * program, a game. Streams are told apart by their picture and sound (4K, HDR, Atmos, size,
 * language), read from what their addon wrote, never shown as it was written.
 */
object SourcePrivacy {

    /** A stream as the app shows it: its picture ("4K · Dolby Vision") and the rest ("12.4 GB · Atmos · 🇪🇸"). */
    data class StreamLabel(val title: String, val details: String?)

    fun streamLabel(stream: Stream, fallback: String): StreamLabel {
        val text = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename,
            stream.quality
        ).joinToString(" ")
        val lower = text.lowercase(Locale.ROOT)

        val resolution = resolutionOf(lower)
            ?: stream.qualityValue.takeIf { it > 0 }?.let { resolutionOfHeight(it) }
        val picture = buildList {
            when {
                DOLBY_VISION.containsMatchIn(lower) -> add("Dolby Vision")
            }
            when {
                HDR10_PLUS.containsMatchIn(lower) -> add("HDR10+")
                HDR.containsMatchIn(lower) -> add("HDR")
            }
            if (THREE_D.containsMatchIn(lower)) add("3D")
        }
        val title = (listOfNotNull(resolution) + picture).joinToString(" · ").ifBlank { fallback }

        val size = stream.behaviorHints?.videoSize?.takeIf { it > 0 }?.let(::formatSize) ?: sizeIn(text)
        val sound = buildList {
            if (ATMOS.containsMatchIn(lower)) add("Atmos")
            CHANNELS.find(lower)?.value?.let { add(it) }
        }.joinToString(" ").ifBlank { null }
        val codec = when {
            HEVC.containsMatchIn(lower) -> "HEVC"
            AV1.containsMatchIn(lower) -> "AV1"
            else -> null
        }
        val languages = languagesIn(text)
        val details = listOfNotNull(size, sound, codec, languages).joinToString(" · ").ifBlank { null }
        return StreamLabel(title = title, details = details)
    }

    /**
     * A file's or a torrent's name as a title: "John.Wick.2014.2160p.UHD.BluRay.x265-GRP.mkv" →
     * "John Wick (2014)"; "The.Last.of.Us.S01E03.1080p.WEB" → "The Last of Us · S1 E3".
     */
    fun cleanReleaseName(name: String): String {
        var base = name.substringAfterLast('/').trim()
        base = base.replace(EXTENSION, "")
        // Bracketed tags ("[YTS.MX]", "(1080p)") and website prefixes ("www.site.org - ").
        base = base.replace(WEBSITE_PREFIX, "").replace(BRACKETS, " ")
        val spaced = base.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()
        val episode = EPISODE.find(spaced)
        if (episode != null) {
            val show = spaced.substring(0, episode.range.first).trim(' ', '-')
            val season = episode.groupValues[1].toIntOrNull()
            val number = episode.groupValues[2].toIntOrNull()
            val label = listOfNotNull(season?.let { "S$it" }, number?.let { "E$it" }).joinToString(" ")
            return listOf(show, label).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { spaced }
        }
        val year = YEAR.find(spaced)
        if (year != null && year.range.first > 0) {
            val title = spaced.substring(0, year.range.first).trim(' ', '-', '(')
            if (title.isNotBlank()) return "$title (${year.groupValues[1]})"
        }
        val cut = RELEASE_TAG.find(spaced)?.range?.first?.takeIf { it > 0 }
        return (if (cut != null) spaced.substring(0, cut) else spaced).trim(' ', '-').ifBlank { spaced }
    }

    /**
     * A message with its sources taken out: web addresses, server names and the services' and
     * addons' names ("Unable to resolve host "api.real-debrid.com"" → "Unable to resolve host").
     */
    fun scrubText(text: String): String {
        var out = text.replace(URL, " ").replace(HOST, " ").replace(IP, " ")
        SERVICES.forEach { name -> out = out.replace(name, " ") }
        return out
            .replace(Regex("""["'“”]\s*["'“”]"""), " ")
            .replace(Regex("""\(\s*\)|\[\s*\]"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', ':', '-', '·', ',', '|')
    }

    /**
     * An error's text, line by line, without web addresses, servers' addresses or the services'
     * and addons' names (the app's own class names stay: they say where it happened).
     */
    fun scrubCrash(text: String): String = text.lines().joinToString("\n") { line ->
        var out = line.replace(URL, "…").replace(IP, "…")
        SERVICES.forEach { name -> out = out.replace(name, "…") }
        out
    }

    private val URL = Regex("""(https?|magnet|torrent):\S+""", RegexOption.IGNORE_CASE)
    private val HOST = Regex(
        """\b(?:[a-z0-9-]+\.)+(?:com|net|org|io|app|tv|me|link|cc|to|xyz|club|live|pro|info|biz|fun|site|stream|cloud|eu|us|uk|de|fr|es|nl|ru|top|vip)\b(?::\d+)?""",
        RegexOption.IGNORE_CASE
    )
    private val IP = Regex("""/?\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""")
    /** The debrid services and stream addons people run. */
    private val SERVICES = listOf(
        "real[- ]?debrid", "\\bRD\\+?(?=\\W)", "all[- ]?debrid", "premiumize", "tor[- ]?box", "debrid[- ]?link", "easy[- ]?debrid",
        "offcloud", "put\\.io", "easynews", "torrentio", "aio[- ]?streams?", "mediafusion", "comet", "jackettio", "jackett",
        "knightcrawler", "stremthru", "orion", "peerflix", "torrentsdb", "debridio", "zilean", "prowlarr"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private fun resolutionOf(lower: String): String? = when {
        Regex("""\b(2160p|4k|uhd)\b""").containsMatchIn(lower) -> "4K"
        Regex("""\b1440p\b""").containsMatchIn(lower) -> "1440p"
        Regex("""\b(1080p|1080i|fhd)\b""").containsMatchIn(lower) -> "1080p"
        Regex("""\b720p\b""").containsMatchIn(lower) -> "720p"
        Regex("""\b(576p|480p|360p|sd)\b""").containsMatchIn(lower) -> "SD"
        else -> null
    }

    private fun resolutionOfHeight(height: Int): String = when {
        height >= 2000 -> "4K"
        height >= 1400 -> "1440p"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        else -> "SD"
    }

    private fun sizeIn(text: String): String? {
        val match = SIZE.find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase(Locale.ROOT).let { if (it.startsWith("G")) "GB" else if (it.startsWith("M")) "MB" else "TB" }
        return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, unit)
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
    }

    /** The flags a stream lists for its audio, and the Spanish words for it ("Latino", "Castellano"). */
    private fun languagesIn(text: String): String? {
        val flags = FLAG.findAll(text).map { it.value }.distinct().take(4).toList()
        val words = LANGUAGE_WORDS.mapNotNull { (pattern, label) -> label.takeIf { pattern.containsMatchIn(text) } }.distinct()
        return (flags + words).joinToString(" ").ifBlank { null }
    }

    private val DOLBY_VISION = Regex("""\b(dv|dovi|dolby[ .]?vision)\b""")
    private val HDR10_PLUS = Regex("""hdr10(\+|plus)""")
    private val HDR = Regex("""\bhdr(10)?\b""")
    private val THREE_D = Regex("""\b(3d|sbs|h-?ou)\b""")
    private val ATMOS = Regex("""\batmos\b""")
    private val CHANNELS = Regex("""\b[257]\.[01]\b""")
    private val HEVC = Regex("""\b(hevc|x265|h\.?265)\b""")
    private val AV1 = Regex("""\bav1\b""")
    private val SIZE = Regex("""(\d+(?:[.,]\d+)?)\s?(GB|GiB|MB|MiB|TB)\b""", RegexOption.IGNORE_CASE)
    /** A flag: two regional-indicator letters. */
    private val FLAG = Regex("""[\x{1F1E6}-\x{1F1FF}]{2}""")
    private val LANGUAGE_WORDS = listOf(
        Regex("""\blatino\b""", RegexOption.IGNORE_CASE) to "Latino",
        Regex("""\bcastellano\b""", RegexOption.IGNORE_CASE) to "Castellano",
        Regex("""\b(dual|multi)(\s?audio)?\b""", RegexOption.IGNORE_CASE) to "Multi"
    )

    private val EXTENSION = Regex("""\.(mkv|mp4|avi|m4v|mov|ts|wmv|webm|iso)$""", RegexOption.IGNORE_CASE)
    private val WEBSITE_PREFIX = Regex("""^\s*(www\.)?[a-z0-9-]+\.(com|org|net|io|to|mx|se|cc|xyz|tv)\s*-\s*""", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("""\[[^\]]*\]|\{[^\}]*\}""")
    private val EPISODE = Regex("""\bS(\d{1,2})\s?E(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""\(?\b((?:19|20)\d{2})\b\)?""")
    private val RELEASE_TAG = Regex(
        """\b(2160p|1080p|720p|480p|4k|uhd|web[ -]?dl|webrip|bluray|bdrip|brrip|hdtv|remux|x264|x265|hevc|h264|h265|proper|repack|complete|season)\b""",
        RegexOption.IGNORE_CASE
    )
}
