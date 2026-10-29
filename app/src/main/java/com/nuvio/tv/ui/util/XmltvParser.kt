package com.nuvio.tv.ui.util

import com.nuvio.tv.core.util.GuideText
import android.util.Xml
import com.nuvio.tv.domain.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.StringReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/** A channel declared in an XMLTV feed: its id, first display name and logo. */
data class XmltvChannel(
    val id: String,
    val displayName: String,
    val iconUrl: String?,
    /** A display name that is just the channel number ("7.1", "602"), if any. */
    val number: String? = null
)

/** Channels and programs read from one XMLTV feed. */
data class XmltvGuide(
    val channels: List<XmltvChannel>,
    val programs: List<EpgProgram>
)

/**
 * Streaming parser for XMLTV EPG feeds (the de-facto standard EPG format used by IPTV providers,
 * both via Xtream's `xmltv.php` export and standalone EPG URLs paired with M3U playlists).
 * Only pulls the fields the Live Guide needs; unknown elements are skipped.
 */
object XmltvParser {

    private val GZIP_MAGIC_1 = 0x1f
    private val GZIP_MAGIC_2 = 0x8b

    // RCTV (MEM-07): a channel's number and a program's [LIVE]/[NEW] tags are read without regular
    // expressions (GuideText): they ran for every program of the guide, leaving native memory.

    /** Sports categories: a new airing of one of these is a live game or live sports show. */
    private val LIVE_SPORT_CATEGORIES = setOf(
        "sports", "football", "basketball", "soccer", "baseball", "hockey", "ice hockey", "sports event",
        "playoff sports", "multi-sport event", "motorsports", "auto racing", "boxing", "mixed martial arts",
        "mma", "tennis", "golf", "wrestling", "volleyball", "rugby", "cricket", "softball", "lacrosse",
        "australian rules football", "3x3 basketball", "fútbol", "deportes"
    )

    /**
     * Fast path for the usual XMLTV time, "yyyyMMddHHmmss +hhmm". Returns null when the value has
     * no zone offset (or isn't in that shape), so the caller falls back to [SimpleDateFormat].
     */
    private fun parseXmltvDateFast(raw: String): Long? {
        if (raw.length < 14) return null
        for (i in 0 until 14) if (raw[i] !in '0'..'9') return null
        fun num(from: Int, to: Int): Int {
            var value = 0
            for (i in from until to) value = value * 10 + (raw[i] - '0')
            return value
        }
        val rest = raw.substring(14).trim()
        if (rest.length != 5 || (rest[0] != '+' && rest[0] != '-')) return null
        for (i in 1 until 5) if (rest[i] !in '0'..'9') return null
        val offsetMinutes = (rest.substring(1, 3).toInt() * 60 + rest.substring(3, 5).toInt()) *
            (if (rest[0] == '-') -1 else 1)
        val year = num(0, 4)
        val month = num(4, 6)
        val day = num(6, 8)
        if (month !in 1..12 || day !in 1..31) return null
        val epochDays = daysFromCivil(year, month, day)
        val seconds = epochDays * 86_400L + num(8, 10) * 3_600L + num(10, 12) * 60L + num(12, 14) -
            offsetMinutes * 60L
        return seconds * 1_000L
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val monthIndex = (month + 9) % 12
        val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era.toLong() * 146_097L + dayOfEra - 719_468L
    }

    private fun parseXmltvDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        parseXmltvDateFast(trimmed)?.let { return it }
        // Rare shapes (no zone offset): local time, as before. Fresh formats keep this thread-safe.
        val formats = listOf(
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        )
        for (format in formats) {
            val position = ParsePosition(0)
            val date = format.parse(trimmed, position)
            if (date != null && position.index > 0) return date.time
        }
        return null
    }

    fun parse(xmltvText: String): List<EpgProgram> {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmltvText))
        return read(parser, Long.MIN_VALUE, Long.MAX_VALUE, includeChannels = false, shouldContinue = { true }, keepChannel = null).programs
    }

    /**
     * Reads an XMLTV feed straight from [input] without loading the whole file into memory (big
     * national guides are well over 100 MB once unzipped). Gzip files (`.xml.gz`) are detected
     * from their first bytes and unzipped on the fly. Only programs that overlap
     * [fromMs, toMs) are kept; the rest are skipped without reading their text.
     * [shouldContinue] is checked now and then, so a cancelled load stops early.
     */
    fun parseStream(
        input: InputStream,
        fromMs: Long = Long.MIN_VALUE,
        toMs: Long = Long.MAX_VALUE,
        includeChannels: Boolean = true,
        shouldContinue: () -> Boolean = { true },
        /**
         * Only these channels' programs are kept (a provider's guide often covers thousands of
         * channels its playlist doesn't have); null: every channel's.
         */
        keepChannel: ((String) -> Boolean)? = null
    ): XmltvGuide {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val source: InputStream = if (first == GZIP_MAGIC_1 && second == GZIP_MAGIC_2) {
            GZIPInputStream(buffered, 64 * 1024)
        } else {
            buffered
        }
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(source, null)
        return read(parser, fromMs, toMs, includeChannels, shouldContinue, keepChannel)
    }

    /** Skips the element the parser is on (its whole subtree), ending on its end tag. */
    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun read(
        parser: XmlPullParser,
        fromMs: Long,
        toMs: Long,
        includeChannels: Boolean,
        shouldContinue: () -> Boolean,
        keepChannel: ((String) -> Boolean)?
    ): XmltvGuide {
        val channels = mutableListOf<XmltvChannel>()
        val programs = mutableListOf<EpgProgram>()

        // <channel>
        var inChannel = false
        var channelDeclId: String? = null
        var channelName: String? = null
        var channelIcon: String? = null
        var channelNumber: String? = null

        // <programme>
        var inProgramme = false
        var channelId: String? = null
        var startMs: Long? = null
        var endMs: Long? = null
        var title: String? = null
        var description: String? = null
        var category: String? = null
        var subtitle: String? = null
        var imageUrl: String? = null
        var markedLive = false
        var markedNew = false
        val categories = ArrayList<String>(4)
        // The same few category names repeat thousands of times: keep one copy of each. The same
        // goes for the channel ids and the shows' titles (a show airs many times in two days).
        val categoryNames = HashMap<String, String>()
        val sharedStrings = HashMap<String, String>()
        fun shared(text: String): String = sharedStrings.getOrPut(text) { text }

        var currentTag: String? = null
        var seen = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    when {
                        tag == "channel" && !inProgramme -> {
                            if (includeChannels) {
                                inChannel = true
                                channelDeclId = parser.getAttributeValue(null, "id")
                                channelName = null
                                channelIcon = null
                                channelNumber = null
                            } else {
                                try { skipElement(parser) } catch (_: Exception) { break }
                            }
                        }
                        tag == "programme" -> {
                            if (++seen % 2_000 == 0 && !shouldContinue()) break
                            val programmeChannel = parser.getAttributeValue(null, "channel")
                            val wanted = keepChannel == null || (programmeChannel != null && keepChannel(programmeChannel))
                            val start = if (wanted) parseXmltvDate(parser.getAttributeValue(null, "start")) else null
                            val end = if (wanted) parseXmltvDate(parser.getAttributeValue(null, "stop")) else null
                            if (!wanted || start == null || end == null || end <= fromMs || start >= toMs) {
                                // Outside the window (or unusable): don't read its text at all.
                                try { skipElement(parser) } catch (_: Exception) { break }
                            } else {
                                inProgramme = true
                                channelId = programmeChannel?.let(::shared)
                                startMs = start
                                endMs = end
                                title = null
                                description = null
                                category = null
                                subtitle = null
                                imageUrl = null
                                markedLive = false
                                markedNew = false
                                categories.clear()
                            }
                        }
                        inChannel && tag == "display-name" -> currentTag = tag
                        inChannel && tag == "icon" -> {
                            if (channelIcon == null) {
                                channelIcon = parser.getAttributeValue(null, "src")?.trim()?.takeIf { it.isNotBlank() }
                            }
                        }
                        inProgramme && (tag == "title" || tag == "desc" || tag == "category" || tag == "sub-title") -> currentTag = tag
                        inProgramme && tag == "icon" -> {
                            if (imageUrl == null) {
                                imageUrl = parser.getAttributeValue(null, "src")?.trim()?.takeIf { it.isNotBlank() }
                            }
                        }
                        inProgramme && tag == "live" -> markedLive = true
                        inProgramme && tag == "new" -> markedNew = true
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrBlank()) {
                        if (inProgramme) {
                            when (currentTag) {
                                "title" -> title = (title.orEmpty() + text).takeIf { it.isNotBlank() }
                                "desc" -> description = (description.orEmpty() + text).takeIf { it.isNotBlank() }
                                "sub-title" -> subtitle = (subtitle.orEmpty() + text).takeIf { it.isNotBlank() }
                                "category" -> {
                                    val name = categoryNames.getOrPut(text) { text }
                                    if (category == null) category = name
                                    categories += name
                                }
                            }
                        } else if (inChannel && currentTag == "display-name") {
                            if (channelNumber == null && GuideText.isChannelNumber(text)) {
                                channelNumber = text
                            } else if (channelName == null) {
                                channelName = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "title", "desc", "category", "sub-title", "display-name" -> currentTag = null
                        "channel" -> if (inChannel) {
                            val id = channelDeclId
                            if (!id.isNullOrBlank()) {
                                channels += XmltvChannel(
                                    id = id,
                                    displayName = channelName ?: channelNumber ?: id,
                                    iconUrl = channelIcon,
                                    number = channelNumber
                                )
                            }
                            inChannel = false
                        }
                        "programme" -> if (inProgramme) {
                            val id = channelId
                            val start = startMs
                            val end = endMs
                            val programTitle = title
                            if (!id.isNullOrBlank() && start != null && end != null && end > start && !programTitle.isNullOrBlank()) {
                                val isNew = markedNew || GuideText.hasBracketTag(programTitle, "new")
                                val isSportsGame = categories.any { it.lowercase(Locale.ROOT) in LIVE_SPORT_CATEGORIES }
                                programs += EpgProgram(
                                    channelId = id,
                                    title = shared(programTitle),
                                    // Reruns repeat the same description, episode and art.
                                    description = description?.let(::shared),
                                    category = category,
                                    startMs = start,
                                    endMs = end,
                                    subtitle = subtitle?.let(::shared),
                                    imageUrl = imageUrl?.let(::shared),
                                    isLive = markedLive || GuideText.hasBracketTag(programTitle, "live") || (isNew && isSportsGame),
                                    isNew = isNew,
                                    categories = categories.toList()
                                )
                            }
                            inProgramme = false
                        }
                    }
                }
            }
            event = try {
                parser.next()
            } catch (_: Exception) {
                break
            }
        }

        return XmltvGuide(channels = channels, programs = programs)
    }
}
