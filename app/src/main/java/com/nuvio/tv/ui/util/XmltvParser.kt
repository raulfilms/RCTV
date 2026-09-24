package com.nuvio.tv.ui.util

import android.util.Xml
import com.nuvio.tv.domain.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Streaming parser for XMLTV EPG feeds (the de-facto standard EPG format used by IPTV providers,
 * both via Xtream's `xmltv.php` export and standalone EPG URLs paired with M3U playlists).
 * Only pulls the fields the Live Guide needs; unknown elements are skipped.
 */
object XmltvParser {

    private val dateFormats = listOf(
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    )

    private fun parseXmltvDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        for (format in dateFormats) {
            val position = ParsePosition(0)
            val date = format.parse(trimmed, position)
            if (date != null && position.index > 0) return date.time
        }
        return null
    }

    fun parse(xmltvText: String): List<EpgProgram> {
        val programs = mutableListOf<EpgProgram>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmltvText))

        var channelId: String? = null
        var startMs: Long? = null
        var endMs: Long? = null
        var title: String? = null
        var description: String? = null
        var category: String? = null
        var currentTag: String? = null
        var inProgramme = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    when (tag) {
                        "programme" -> {
                            inProgramme = true
                            channelId = parser.getAttributeValue(null, "channel")
                            startMs = parseXmltvDate(parser.getAttributeValue(null, "start"))
                            endMs = parseXmltvDate(parser.getAttributeValue(null, "stop"))
                            title = null
                            description = null
                            category = null
                        }
                        "title", "desc", "category" -> currentTag = tag
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inProgramme) {
                        val text = parser.text?.trim()
                        if (!text.isNullOrBlank()) {
                            when (currentTag) {
                                "title" -> title = (title.orEmpty() + text).takeIf { it.isNotBlank() }
                                "desc" -> description = (description.orEmpty() + text).takeIf { it.isNotBlank() }
                                "category" -> if (category == null) category = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "title", "desc", "category" -> currentTag = null
                        "programme" -> {
                            val id = channelId
                            val start = startMs
                            val end = endMs
                            val programTitle = title
                            if (!id.isNullOrBlank() && start != null && end != null && end > start && !programTitle.isNullOrBlank()) {
                                programs += EpgProgram(
                                    channelId = id,
                                    title = programTitle,
                                    description = description,
                                    category = category,
                                    startMs = start,
                                    endMs = end
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

        return programs
    }
}
