package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.data.remote.espn.NascarSource
import com.nuvio.tv.data.remote.sportsdb.PpvEventsSource
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import com.nuvio.tv.domain.model.SportsRaceResult

// ---------------------------------------------------------------------------------------------
// Events of their own (a golf tournament, a race, a PPV show), not games between two teams.
// ---------------------------------------------------------------------------------------------

/** A race (NASCAR, Formula 1). */
internal fun SportsEvent.isRace(): Boolean = leagueId?.startsWith("${NascarSource.SPORT_PATH}:") == true

/** A NASCAR race (its results come from NASCAR). */
internal fun SportsEvent.isNascar(): Boolean = isRace() && leagueId?.substringAfter(':') in NascarSource.LEAGUE_PATHS

/** A PPV Events show: a fight card or a wrestling event. */
internal fun SportsEvent.isPpv(): Boolean = leagueId?.startsWith("${PpvEventsSource.SPORT_PATH}:") == true

/** A fight among PPV Events (two fighters): its highlights are searched as a matchup. */
internal fun SportsEvent.isFight(): Boolean =
    isPpv() && sportName.equals(PpvEventsSource.SPORT_BOXING, ignoreCase = true) && awayTeamName != PpvEventsSource.SPORT_BOXING

/** One event of its own, shown by its name (not two teams): golf, races, PPV Events. */
internal fun SportsEvent.isSoloEvent(): Boolean = isGolf() || isRace() || isPpv()

/** A race's card: night-race navy. */
internal val RaceNavy = Color(0xFF15223F)

/** A PPV show's card: fight-night crimson. */
internal val PpvCrimson = Color(0xFF5C0A14)

/** The color behind an event's card (golf's green, a race's navy, a fight night's crimson). */
internal fun soloEventColor(event: SportsEvent): Color = when {
    event.isGolf() -> GolfGreen
    event.isPpv() -> PpvCrimson
    else -> RaceNavy
}

/**
 * An event on a card: its logo (the tour's, the series', the promotion's), its name big, then who
 * leads or won (golf, races) or where it is. Fits the cards' middle, where two teams go for a game.
 */
@Composable
internal fun SoloEventContent(
    event: SportsEvent,
    modifier: Modifier = Modifier,
    nameSize: TextUnit = 15.sp,
    textColor: Color = Color.White,
    secondaryColor: Color = Color.White.copy(alpha = 0.78f),
    showLogo: Boolean = true
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        event.homeTeamBadgeUrl?.takeIf { showLogo }?.let { logo ->
            SportsLogoImage(
                url = logo,
                background = soloEventColor(event),
                contentDescription = event.leagueName,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.name,
                color = textColor,
                fontSize = nameSize,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val second = golfLeaderLine(event)?.let { leader ->
                if (event.status == SportsEventStatus.FINISHED) "🏆 $leader" else "1. $leader"
            } ?: event.venue?.takeIf { it.isNotBlank() }
            if (second != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = second,
                    color = secondaryColor,
                    fontSize = (nameSize.value - 3f).coerceAtLeast(10f).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The inside of a 16:9 card for an event of its own: its picture (a fight's poster) or its color,
 * its name and who leads (or where), and LIVE or when it starts in the corner.
 */
@Composable
internal fun SoloEventCardBody(event: SportsEvent, status: String, liveBadge: @Composable () -> Unit) {
    val color = soloEventColor(event)
    val image = event.imageUrl?.takeIf { it.isNotBlank() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(color, lerp(color, Color.Black, 0.55f))))
    ) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.10f),
                                0.45f to Color.Black.copy(alpha = 0.30f),
                                1f to Color.Black.copy(alpha = 0.88f)
                            )
                        )
                    )
            )
        }
        SoloEventContent(
            event = event,
            showLogo = image == null,
            modifier = Modifier
                .then(if (image != null) Modifier.align(Alignment.BottomStart) else Modifier.fillMaxSize())
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 10.dp)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            when (event.status) {
                SportsEventStatus.LIVE -> liveBadge()
                else -> Text(
                    text = status,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A race's results (or running order, while it's on).
// ---------------------------------------------------------------------------------------------

/**
 * The results' columns: only what the source has (NASCAR: laps, led, status, points; MotoGP:
 * laps, gap, points; Formula 1 and IndyCar on ESPN: the order alone).
 */
internal data class RaceColumns(
    val laps: Boolean,
    val led: Boolean,
    /** "STATUS" ("Running", "Accident") or "GAP" ("+4.260"); null: none. */
    val statusLabel: String?,
    val points: Boolean
) {
    val widths: List<androidx.compose.ui.unit.Dp>
        get() = listOfNotNull(
            64.dp.takeIf { laps },
            52.dp.takeIf { led },
            120.dp.takeIf { statusLabel != null },
            52.dp.takeIf { points }
        )

    companion object {
        fun of(results: List<SportsRaceResult>): RaceColumns = RaceColumns(
            laps = results.any { it.laps != null },
            led = results.any { (it.lapsLed ?: 0) > 0 },
            statusLabel = when {
                results.none { !it.status.isNullOrBlank() } -> null
                results.count { it.status?.startsWith("+") == true } > results.size / 3 -> "GAP"
                else -> "STATUS"
            },
            points = results.any { it.points != null }
        )
    }
}

/** The results' heading: POS, DRIVER, then the columns there are. */
@Composable
internal fun RaceResultsHeader(columns: RaceColumns, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        RaceRow(
            position = "POS",
            badgeUrl = null,
            driver = "DRIVER",
            team = null,
            values = listOfNotNull(
                "LAPS".takeIf { columns.laps },
                "LED".takeIf { columns.led },
                columns.statusLabel,
                "PTS".takeIf { columns.points }
            ),
            widths = columns.widths,
            header = true
        )
    }
}

/** One driver's place (focusable, to scroll through the field with the remote). */
@Composable
internal fun RaceResultRow(result: SportsRaceResult, columns: RaceColumns, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        RaceRow(
            position = result.position.toString(),
            badgeUrl = result.carBadgeUrl,
            driver = result.carNumber.takeIf { it.isNotBlank() && result.carBadgeUrl?.contains("carbadges") != true }
                ?.let { "${result.driver}  #$it" } ?: result.driver,
            team = result.team,
            values = listOfNotNull(
                result.laps?.toString().orEmpty().takeIf { columns.laps },
                (result.lapsLed?.takeIf { it > 0 }?.toString() ?: "–").takeIf { columns.led },
                result.status.orEmpty().takeIf { columns.statusLabel != null },
                result.points?.toString().orEmpty().takeIf { columns.points }
            ),
            widths = columns.widths,
            header = false
        )
    }
}

@Composable
private fun RaceRow(
    position: String,
    badgeUrl: String?,
    driver: String,
    team: String?,
    values: List<String>,
    widths: List<androidx.compose.ui.unit.Dp>,
    header: Boolean
) {
    var focused by remember { mutableStateOf(false) }
    val labelColor = if (header) Color.White.copy(alpha = 0.55f) else Color.White
    val size = if (header) 11.sp else 15.sp
    val weight = if (header) FontWeight.SemiBold else FontWeight.Medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.16f) else if (header) Color.Transparent else Color.White.copy(alpha = 0.05f))
            .then(if (header) Modifier else Modifier.onFocusChanged { focused = it.isFocused }.focusable())
            .padding(horizontal = 14.dp, vertical = if (header) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = position, color = labelColor, fontSize = size, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
        Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.CenterStart) {
            if (!badgeUrl.isNullOrBlank()) {
                AsyncImage(
                    model = badgeUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 38.dp, height = 24.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = driver,
                color = labelColor,
                fontSize = size,
                fontWeight = if (header) weight else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!header && !team.isNullOrBlank()) {
                Text(
                    text = team,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        values.forEachIndexed { index, value ->
            Text(
                text = value,
                color = labelColor.copy(alpha = if (header) 0.55f else if (index == 0) 1f else 0.82f),
                fontSize = size,
                fontWeight = if (index == values.lastIndex && !header) FontWeight.Bold else weight,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(widths.getOrElse(index) { 60.dp })
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A PPV show's page: its poster, what it's about, its card.
// ---------------------------------------------------------------------------------------------

/** The poster (when there is one) beside what the show is about. */
@Composable
internal fun ShowAbout(posterUrl: String?, imageUrl: String?, description: String?, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        val poster = posterUrl?.takeIf { it.isNotBlank() }
        val wide = imageUrl?.takeIf { it.isNotBlank() }
        when {
            poster != null -> AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(150.dp)
                    .height(222.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            wide != null -> AsyncImage(
                model = wide,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(300.dp)
                    .height(169.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        }
        if ((poster != null || wide != null) && !description.isNullOrBlank()) Spacer(modifier = Modifier.width(20.dp))
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** One line of the card: a fight or a match (a match's kind, "Singles Match", as a small heading). */
@Composable
internal fun ShowCardLine(line: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val kind = MATCH_WORD.containsMatchIn(line) && !VS_WORD.containsMatchIn(line)
    Text(
        text = line,
        color = if (kind) Color.White.copy(alpha = 0.55f) else Color.White,
        fontSize = if (kind) 12.sp else 16.sp,
        fontWeight = if (kind) FontWeight.SemiBold else FontWeight.Medium,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 14.dp, vertical = if (kind) 2.dp else 6.dp)
    )
}

/** "Singles Match", "Tag Team Match for the ...": a match's kind, not who's in it ("A vs. B"). */
private val MATCH_WORD = Regex("""\bmatch\b""", RegexOption.IGNORE_CASE)
private val VS_WORD = Regex("""\bvs?\b\.?""", RegexOption.IGNORE_CASE)
