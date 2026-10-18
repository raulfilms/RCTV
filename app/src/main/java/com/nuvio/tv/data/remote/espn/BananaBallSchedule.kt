package com.nuvio.tv.data.remote.espn

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Banana Ball's teams, and its schedule as the league's site (bananaball.com/schedule) lists it:
 * read from the page itself (it has no data feed).
 */
internal object BananaBallSchedule {

    /** One of the league's teams. */
    data class Team(
        val id: String,
        val name: String,
        val abbreviation: String,
        val location: String?,
        val nickname: String,
        val logoUrl: String,
        val color: String,
        /** How the league's site names it in pictures ("The Savannah Bananas"). */
        val siteNames: List<String>
    )

    /** One game on the league's schedule. */
    data class Game(
        val date: LocalDate,
        val awayTeamId: String?,
        val homeTeamId: String?,
        /** A tournament or event with several teams (no single matchup). */
        val multiTeam: Boolean,
        val venue: String?,
        /** Null: "TBA". */
        val time: LocalTime?,
        val zone: ZoneId,
        val broadcast: String?
    )

    /** When a game with no time yet is put (most start in the evening). */
    val DEFAULT_START: LocalTime = LocalTime.of(19, 0)
    val EASTERN: ZoneId = ZoneId.of("America/New_York")

    private fun logo(slug: String) = "https://bananaball.com/wp-content/uploads/2026/09/team-$slug-4.png?w=256"

    val TEAMS = listOf(
        Team("savannah-bananas", "Savannah Bananas", "SB", "Savannah", "Bananas", logo("savannah-bananas"), "C99A00", listOf("The Savannah Bananas")),
        Team("party-animals", "Party Animals", "PA", null, "Party Animals", logo("party-animals"), "D6006F", listOf("The Party Animals")),
        Team("firefighters", "Firefighters", "FF", null, "Firefighters", logo("firefighters"), "C8102E", listOf("The Firefighters")),
        Team("texas-tailgaters", "Texas Tailgaters", "TT", "Texas", "Tailgaters", logo("texas-tailgaters"), "0C2340", listOf("The Texas Tailgaters")),
        Team("loco-beach-coconuts", "Loco Beach Coconuts", "LBC", null, "Coconuts", logo("loco-beach-coconuts"), "00838F", listOf("The Loco Beach Coconuts")),
        Team("indianapolis-clowns", "Indianapolis Clowns", "IC", "Indianapolis", "Clowns", logo("indianapolis-clowns"), "1E40AF", listOf("The Indianapolis Clowns"))
    )

    /** TV and streaming the schedule names, the longer names first. */
    private val NETWORKS = listOf(
        "ESPN Unlimited", "ESPN2", "ESPN+", "ESPN", "ABC", "The CW", "CW", "truTV", "TBS", "HBO Max",
        "Roku", "Disney+", "FS1", "FOX", "NBC", "Peacock", "Netflix", "Prime Video", "YouTube"
    )

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december"
    )

    /** "Friday, February 5" (a day's heading on the schedule). */
    private val DAY_HEADING = Regex(
        """^(?:mon|tues|wednes|thurs|fri|satur|sun)day,?\s+(january|february|march|april|may|june|july|august|september|october|november|december)\s+(\d{1,2})(?:,?\s+(20\d\d))?""",
        RegexOption.IGNORE_CASE
    )

    private val TIME = Regex("""(\d{1,2})(?::(\d{2}))?\s*([ap])\.?\s*m\.?""", RegexOption.IGNORE_CASE)
    private val ZONE_WORD = Regex("""\b(ET|EST|EDT|CT|CST|CDT|MT|MST|MDT|PT|PST|PDT)\b""")

    private val TOKEN = Regex(
        """<(script|style|noscript)\b.*?</\1\s*>|<h[1-6]\b[^>]*>(.*?)</h[1-6]\s*>|<img\b[^>]*>|<[^>]+>|([^<]+)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private sealed interface Token {
        data class Heading(val text: String) : Token
        data class Image(val alt: String, val src: String) : Token
        data class Text(val text: String) : Token
    }

    private class Pending(val multiTeam: Boolean = false) {
        val teams = ArrayList<String>(2)
        val texts = ArrayList<String>()
    }

    /**
     * The schedule page's games: each day's heading ("Friday, February 5"), then each game's
     * two team pictures (away, then home) and its venue, city, time and where it's on
     * ("Walmart Park Monterrey, Mexico · TBA SB YouTube"). [today] places the days in a year.
     */
    fun parseSchedule(html: String, today: LocalDate): List<Game> {
        val tokens = tokensOf(html)
        var year = seasonYearOf(tokens) ?: today.year
        val yearGiven = seasonYearOf(tokens) != null
        var lastDate: LocalDate? = null
        var date: LocalDate? = null
        var pending: Pending? = null
        val games = ArrayList<Game>()

        fun finish() {
            val current = pending ?: return
            pending = null
            val day = date ?: return
            if (!current.multiTeam && current.teams.size != 2) return
            // A game says where and when ("Walmart Park Monterrey, Mexico · TBA"): pictures
            // without that (the page's own logos, a row of team logos) aren't one.
            val joined = current.texts.joinToString(" ")
            if ('·' !in joined && !TIME.containsMatchIn(joined) && !Regex("\\bTBA\\b").containsMatchIn(joined)) return
            games += gameOf(day, current)
        }

        fun dateOf(match: MatchResult): LocalDate? {
            val month = MONTHS.indexOf(match.groupValues[1].lowercase(Locale.ROOT)) + 1
            val dayOfMonth = match.groupValues[2].toIntOrNull() ?: return null
            match.groupValues[3].toIntOrNull()?.let { year = it }
            var candidate = runCatching { LocalDate.of(year, month, dayOfMonth) }.getOrNull() ?: return null
            val previous = lastDate
            if (previous == null && !yearGiven && candidate.isBefore(today.minusDays(60))) {
                // A season to come, its year not written: next year's.
                year += 1
                candidate = candidate.plusYears(1)
            } else if (previous != null && candidate.isBefore(previous.minusDays(60))) {
                // December, then January: the next year.
                year += 1
                candidate = candidate.plusYears(1)
            }
            lastDate = candidate
            return candidate
        }

        tokens.forEach { token ->
            when (token) {
                is Token.Heading -> DAY_HEADING.find(token.text.trim())?.let { match ->
                    finish()
                    dateOf(match)?.let { date = it }
                }
                is Token.Image -> {
                    val teamId = teamIdOf(token)
                    val multi = teamId == null && isMultiTeam(token)
                    when {
                        teamId != null -> {
                            val current = pending
                            if (current == null || current.multiTeam || current.texts.isNotEmpty() || current.teams.size >= 2) {
                                finish()
                                pending = Pending().also { it.teams += teamId }
                            } else {
                                current.teams += teamId
                            }
                        }
                        multi -> {
                            finish()
                            pending = Pending(multiTeam = true)
                        }
                    }
                }
                is Token.Text -> {
                    val text = token.text
                    val heading = DAY_HEADING.find(text)
                    if (heading != null && heading.range.first == 0 && text.length <= 40) {
                        finish()
                        dateOf(heading)?.let { date = it }
                        return@forEach
                    }
                    val current = pending ?: return@forEach
                    if (!current.multiTeam && current.teams.size < 2) return@forEach
                    if (text.equals("VS", ignoreCase = true) || text.equals("at", ignoreCase = true)) return@forEach
                    if (current.texts.size < 8) current.texts += text
                }
            }
        }
        finish()
        return games.distinct()
    }

    private fun gameOf(date: LocalDate, pending: Pending): Game {
        val joined = pending.texts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        val dot = joined.indexOf('·')
        val venue = (if (dot >= 0) joined.substring(0, dot) else pending.texts.firstOrNull().orEmpty()).trim().ifBlank { null }
        val rest = if (dot >= 0) joined.substring(dot + 1) else pending.texts.drop(1).joinToString(" ")
        val time = TIME.find(rest)?.let { match ->
            val hour = match.groupValues[1].toInt() % 12 + if (match.groupValues[3].equals("p", ignoreCase = true)) 12 else 0
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }
        val zone = when (ZONE_WORD.find(rest)?.value?.take(1)) {
            "C" -> ZoneId.of("America/Chicago")
            "M" -> ZoneId.of("America/Denver")
            "P" -> ZoneId.of("America/Los_Angeles")
            else -> EASTERN
        }
        val broadcast = NETWORKS.firstOrNull { network -> Regex("(?<![A-Za-z])${Regex.escape(network)}(?![A-Za-z])").containsMatchIn(rest) }
        return Game(
            date = date,
            awayTeamId = pending.teams.getOrNull(0),
            homeTeamId = pending.teams.getOrNull(1),
            multiTeam = pending.multiTeam,
            venue = venue,
            time = time,
            zone = zone,
            broadcast = broadcast
        )
    }

    private fun teamIdOf(image: Token.Image): String? {
        val src = image.src.lowercase(Locale.ROOT)
        val alt = image.alt.trim().lowercase(Locale.ROOT).removePrefix("the ").trim()
        return TEAMS.firstOrNull { team ->
            "team-${team.id}" in src || alt == team.name.lowercase(Locale.ROOT)
        }?.id
    }

    private fun isMultiTeam(image: Token.Image): Boolean =
        image.alt.contains("multi", ignoreCase = true) || image.src.contains("/bbcl", ignoreCase = true)

    /** "2027 All months": the season the page shows. */
    private fun seasonYearOf(tokens: List<Token>): Int? {
        tokens.forEachIndexed { index, token ->
            val text = (token as? Token.Text)?.text ?: (token as? Token.Heading)?.text ?: return@forEachIndexed
            Regex("""\b(20\d\d)\s+All months""", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1].toInt() }
            if (text.trim().matches(Regex("20\\d\\d"))) {
                val next = tokens.getOrNull(index + 1)
                val nextText = (next as? Token.Text)?.text ?: (next as? Token.Heading)?.text
                if (nextText?.trim()?.startsWith("All months", ignoreCase = true) == true) return text.trim().toInt()
            }
        }
        return null
    }

    private fun tokensOf(html: String): List<Token> {
        val tokens = ArrayList<Token>()
        TOKEN.findAll(html).forEach { match ->
            val value = match.value
            when {
                match.groups[1] != null -> Unit
                match.groups[2] != null -> {
                    val inner = match.groupValues[2]
                    // A heading can hold pictures too.
                    Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(inner).forEach { tokens += imageOf(it.value) }
                    val text = decode(inner.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
                    if (text.isNotEmpty()) tokens += Token.Heading(text)
                }
                value.startsWith("<img", ignoreCase = true) -> tokens += imageOf(value)
                match.groups[3] != null -> {
                    val text = decode(value).replace(Regex("\\s+"), " ").trim()
                    if (text.isNotEmpty()) tokens += Token.Text(text)
                }
            }
        }
        return tokens
    }

    private fun imageOf(tag: String): Token.Image {
        fun attribute(name: String): String =
            Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)?.let { match ->
                match.groupValues[2].ifEmpty { match.groupValues[3] }
            }.orEmpty()
        return Token.Image(alt = decode(attribute("alt")), src = attribute("src").ifEmpty { attribute("data-src") })
    }

    private fun decode(text: String): String = text
        .replace(Regex("&#(\\d+);")) { match -> match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value }
        .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { match -> match.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value }
        .replace("&nbsp;", " ")
        .replace("&middot;", "·")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&rsquo;", "’")
        .replace("&ndash;", "–")

    /** ESPN's "yyyyMMdd" / "yyyyMMdd-yyyyMMdd" as its first and last day keys. */
    fun rangeOf(datesRange: String?): Pair<String?, String?> {
        if (datesRange.isNullOrBlank()) return null to null
        val parts = datesRange.split('-')
        return parts.first() to parts.last()
    }
}
