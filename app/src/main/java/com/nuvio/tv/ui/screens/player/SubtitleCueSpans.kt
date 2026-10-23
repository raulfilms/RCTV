package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.subsync.CueSpan

/**
 * RCTV: the spoken lines of a subtitle file (SRT, WebVTT, ASS/SSA), as its file times them: songs
 * ("♪ ... ♪") and lines that only describe a sound ("[door slams]") are left out, as nobody talks
 * there (what the subtitle is synced to the audio by).
 */
internal fun subtitleCueSpansOf(body: String, url: String): List<CueSpan> {
    val ass = assCueSpans(body)
    if (ass != null) return ass
    return PlayerSubtitleCueParser.parseFromText(body, url)
        .filter { isSpokenLine(it.text) }
        .map { CueSpan(it.startTimeMs, it.endTimeMs) }
}

private val SOUND_DESCRIPTION = Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}|<[^>]*>|[♪♫#*]""")

/** A line someone says: not a song (♪), not only a sound's description. */
private fun isSpokenLine(text: String): Boolean =
    '♪' !in text && '♫' !in text && text.replace(SOUND_DESCRIPTION, " ").any { it.isLetter() }

private val ASS_TIME = Regex("""(\d+):(\d{1,2}):(\d{1,2})[.,](\d{1,3})""")

/** An ASS/SSA file's dialogue lines; null when it isn't one. */
private fun assCueSpans(body: String): List<CueSpan>? {
    if (!body.contains("[Events]", ignoreCase = true)) return null
    var startField = 1
    var endField = 2
    var fieldCount = 10
    val spans = ArrayList<CueSpan>()
    for (rawLine in body.lineSequence()) {
        val line = rawLine.trim()
        if (line.startsWith("Format:", ignoreCase = true)) {
            val fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            if ("start" in fields && "end" in fields) {
                startField = fields.indexOf("start")
                endField = fields.indexOf("end")
                fieldCount = fields.size
            }
            continue
        }
        if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
        val fields = line.substringAfter(':').split(',', limit = fieldCount)
        val start = fields.getOrNull(startField)?.let(::assTimeMs) ?: continue
        val end = fields.getOrNull(endField)?.let(::assTimeMs) ?: continue
        val text = fields.lastOrNull()?.replace("\\N", " ")?.replace("\\n", " ").orEmpty()
        if (end > start && isSpokenLine(text)) spans += CueSpan(start, end)
    }
    return spans.sortedBy { it.startMs }
}

private fun assTimeMs(value: String): Long? {
    val match = ASS_TIME.find(value.trim()) ?: return null
    val (h, m, s, fraction) = match.destructured
    val millis = fraction.padEnd(3, '0').take(3).toLong()
    return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1_000 + millis
}
