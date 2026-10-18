package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusable
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.data.remote.espn.EspnSportsClient
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A golf tournament (a leaderboard of players), not a game between two teams. */
internal fun SportsEvent.isGolf(): Boolean =
    leagueId?.startsWith("${EspnSportsClient.GOLF}:") == true || sportName.equals("Golf", ignoreCase = true)

/** Golf's green, behind a tournament's card. */
internal val GolfGreen = Color(0xFF1F5130)

/** A tournament's days: "Oct 22 – 25", "Oct 29 – Nov 1". */
internal fun golfDatesLabel(event: SportsEvent): String {
    val start = event.startTimeMs ?: return ""
    val end = event.endTimeMs ?: return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(start))
    val startDay = Calendar.getInstance().apply { timeInMillis = start }
    val endDay = Calendar.getInstance().apply { timeInMillis = end }
    val sameMonth = startDay.get(Calendar.MONTH) == endDay.get(Calendar.MONTH)
    val first = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(start))
    val last = SimpleDateFormat(if (sameMonth) "d" else "MMM d", Locale.getDefault()).format(Date(end))
    return "$first – $last"
}

/** Where a tournament is at: the round (live), Final, or its days. */
internal fun golfStatusText(event: SportsEvent): String = when (event.status) {
    SportsEventStatus.LIVE -> event.statusDetail?.takeIf { it.isNotBlank() } ?: "Live"
    SportsEventStatus.FINISHED -> event.statusDetail?.takeIf { it.isNotBlank() } ?: "Final"
    SportsEventStatus.UPCOMING -> golfDatesLabel(event)
}

/** "1. Scottie Scheffler  -12": who leads (or won), once it's begun. */
internal fun golfLeaderLine(event: SportsEvent): String? {
    val leader = event.leaderName ?: return null
    return listOfNotNull(leader, event.leaderScore?.takeIf { it.isNotBlank() && it != "-" }).joinToString("  ")
}

/**
 * A tournament on a card: the tour's logo, its name big, then its leader (or its days before it
 * starts). Fits the cards' middle, where two teams go for a game.
 */
@Composable
internal fun GolfCardContent(
    event: SportsEvent,
    modifier: Modifier = Modifier,
    nameSize: TextUnit = 15.sp,
    textColor: Color = Color.White,
    secondaryColor: Color = Color.White.copy(alpha = 0.78f)
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        event.homeTeamBadgeUrl?.let { logo ->
            SportsLogoImage(
                url = logo,
                background = GolfGreen,
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

/** The leaderboard's columns: how many rounds, and whether "thru" shows (a round on now). */
internal data class GolfColumns(val rounds: Int, val showThru: Boolean) {
    companion object {
        fun of(players: List<EspnSportsClient.GolfPlayer>) = GolfColumns(
            rounds = players.maxOfOrNull { it.rounds.size }?.coerceAtMost(4) ?: 0,
            showThru = players.any { !it.thru.isNullOrBlank() }
        )
    }
}

/** The leaderboard's heading: POS, PLAYER, TO PAR, THRU, R1... */
@Composable
internal fun GolfLeaderboardHeader(columns: GolfColumns, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        GolfRow(
            position = "POS",
            name = "PLAYER",
            flagUrl = null,
            toPar = "TO PAR",
            thru = if (columns.showThru) "THRU" else null,
            rounds = (1..columns.rounds).map { "R$it" },
            header = true
        )
    }
}

/** One player on the leaderboard (focusable, to scroll through it with the remote). */
@Composable
internal fun GolfLeaderboardRow(player: EspnSportsClient.GolfPlayer, columns: GolfColumns, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        GolfRow(
            position = player.position,
            name = player.name,
            flagUrl = player.flagUrl,
            toPar = player.toPar,
            thru = if (columns.showThru) player.thru.orEmpty() else null,
            rounds = (0 until columns.rounds).map { player.rounds.getOrNull(it).orEmpty() },
            header = false
        )
    }
}

@Composable
private fun GolfRow(
    position: String,
    name: String,
    flagUrl: String?,
    toPar: String,
    thru: String?,
    rounds: List<String>,
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
            .padding(horizontal = 14.dp, vertical = if (header) 4.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = position, color = labelColor, fontSize = size, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            if (!flagUrl.isNullOrBlank()) {
                AsyncImage(
                    model = flagUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 20.dp, height = 14.dp)
                )
            }
        }
        Text(
            text = name,
            color = labelColor,
            fontSize = size,
            fontWeight = if (header) weight else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = toPar,
            color = if (!header && toPar.startsWith("-")) Color(0xFFFF6B6B) else labelColor,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(72.dp)
        )
        if (thru != null) {
            Text(text = thru, color = labelColor, fontSize = size, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
        }
        rounds.forEach { round ->
            Text(text = round, color = labelColor.copy(alpha = if (header) 0.55f else 0.8f), fontSize = size, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        }
    }
}
