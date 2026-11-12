package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.GameCenter
import com.nuvio.tv.domain.model.GameCenterBoxScore
import com.nuvio.tv.domain.model.GameCenterLeader
import com.nuvio.tv.domain.model.GameCenterPlay
import com.nuvio.tv.domain.model.GameCenterTeam
import com.nuvio.tv.domain.model.SportsEvent
import com.nuvio.tv.domain.model.SportsEventStatus
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The game center's tabs, as YouTube TV's. */
enum class GameCenterTab { SCORES, STATS, KEY_PLAYS }

private val CardColor = Color(0xFF2B2B2F)
private val CardShape = RoundedCornerShape(16.dp)
private val Dim = Color.White.copy(alpha = 0.62f)
private val Divider = Color.White.copy(alpha = 0.14f)

/**
 * RCTV (game center, as YouTube TV): the panel beside the video while a live game plays: tabs
 * (Scores, Stats, Key plays) over a list of cards, kept up to date by [LiveGameCenterViewModel].
 * Up and Down move between the cards (and up to the tabs); Left and Right change tabs; Back (in the
 * player) closes it. Only the cards on screen are built, and only they load their pictures.
 */
@Composable
fun LiveGameCenterPanel(
    state: GameCenterUiState,
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableStateOf(GameCenterTab.SCORES) }
    val tabFocus = remember { GameCenterTab.entries.associateWith { FocusRequester() } }
    val listEntry = remember { FocusRequester() }

    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GameCenterTab.entries.forEach { entry ->
                TabPill(
                    text = stringResource(
                        when (entry) {
                            GameCenterTab.SCORES -> R.string.game_center_scores
                            GameCenterTab.STATS -> R.string.game_center_stats
                            GameCenterTab.KEY_PLAYS -> R.string.game_center_key_plays
                        }
                    ),
                    selected = tab == entry,
                    focusRequester = tabFocus.getValue(entry),
                    onFocused = { tab = entry }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            when (tab) {
                GameCenterTab.SCORES -> scoresItems(state, listEntry)
                GameCenterTab.STATS -> statsItems(state, listEntry)
                GameCenterTab.KEY_PLAYS -> playsItems(state, listEntry)
            }
        }
    }

    // Opened: the first card of the tab (or the tab, while there are none yet).
    LaunchedEffect(Unit) {
        delay(FOCUS_DELAY_MS)
        val onCard = runCatching { listEntry.requestFocus() }.isSuccess
        if (!onCard) runCatching { tabFocus.getValue(tab).requestFocus() }
    }
}

private const val FOCUS_DELAY_MS = 120L

// ---------------------------------------------------------------------- Tabs and cards

@Composable
private fun TabPill(text: String, selected: Boolean, focusRequester: FocusRequester, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        focused -> Color.White
        selected -> Color.White.copy(alpha = 0.34f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** A card of the list: dark, rounded, a white frame when it has the focus. */
@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clip(CardShape)
            .background(CardColor)
            .then(if (focused) Modifier.border(3.dp, Color.White, CardShape) else Modifier)
            .padding(16.dp)
    ) {
        content()
    }
}

private fun LazyListScope.message(text: Int) {
    item(key = "message") {
        Text(
            text = stringResource(text),
            color = Dim,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
        )
    }
}

private fun Modifier.entryIf(first: Boolean, requester: FocusRequester): Modifier =
    if (first) focusRequester(requester) else this

// ---------------------------------------------------------------------- Scores

private fun LazyListScope.scoresItems(state: GameCenterUiState, entry: FocusRequester) {
    if (state.games.isEmpty()) {
        message(if (state.isLoading) R.string.game_center_loading else R.string.game_center_no_games)
        return
    }
    itemsIndexed(state.games, key = { _, game -> game.id }) { index, game ->
        GameScoreCard(game = game, isPlaying = game.id == state.eventId, modifier = Modifier.entryIf(index == 0, entry))
    }
}

@Composable
private fun GameScoreCard(game: SportsEvent, isPlaying: Boolean, modifier: Modifier) {
    val homeFirst = game.leagueId?.startsWith("soccer:") == true
    val home = Triple(game.homeTeamBadgeUrl, game.homeTeamName, game.homeScore)
    val away = Triple(game.awayTeamBadgeUrl, game.awayTeamName, game.awayScore)
    val (first, second) = if (homeFirst) home to away else away to home
    val showScores = game.status != SportsEventStatus.UPCOMING
    PanelCard(modifier = modifier) {
        Column {
            listOf(first, second).forEachIndexed { i, (badge, name, score) ->
                if (i > 0) Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(badge, 30.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = name,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (showScores) score?.toString() ?: "-" else "-",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val left = when (game.status) {
                    SportsEventStatus.LIVE -> stringResource(R.string.game_center_live)
                    SportsEventStatus.FINISHED -> stringResource(R.string.game_center_final)
                    SportsEventStatus.UPCOMING -> game.startTimeMs?.let(::timeOf).orEmpty()
                }
                Text(
                    text = left,
                    color = if (game.status == SportsEventStatus.LIVE) Color.White else Dim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (isPlaying) {
                    Text(
                        text = stringResource(R.string.player_live_playing),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (game.status == SportsEventStatus.LIVE) {
                    Text(
                        text = clockLine(game.statusDetail),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** ESPN's "2nd - 0:21" as YouTube TV writes it: "2ND•0:21". */
private fun clockLine(detail: String?): String =
    detail?.uppercase(Locale.getDefault())?.replace(" - ", "•")?.trim().orEmpty()

private fun timeOf(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

// ---------------------------------------------------------------------- Stats

private fun LazyListScope.statsItems(state: GameCenterUiState, entry: FocusRequester) {
    val center = state.center
    if (center == null) {
        message(if (state.isLoading) R.string.game_center_loading else R.string.game_center_no_stats)
        return
    }
    item(key = "board") { ScoreboardCard(center, Modifier.entryIf(true, entry)) }
    if (center.teamStats.isNotEmpty()) item(key = "team_stats") { TeamStatsCard(center) }
    itemsIndexed(center.leaders, key = { i, leader -> "leader_${i}_${leader.name}" }) { _, leader -> LeaderCard(leader) }
    itemsIndexed(center.boxScores, key = { i, box -> "box_${i}_${box.team.abbreviation}" }) { _, box -> BoxScoreCard(box) }
}

@Composable
private fun ScoreboardCard(center: GameCenter, modifier: Modifier) {
    PanelCard(modifier = modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(center.left.logoUrl, 52.dp)
                TeamScore(center.left, Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    val top = center.periodLabel ?: when (center.status) {
                        SportsEventStatus.FINISHED -> stringResource(R.string.game_center_final)
                        else -> center.statusDetail.orEmpty()
                    }
                    Text(text = top, color = Color.White, fontSize = 15.sp, maxLines = 2, textAlign = TextAlign.Center)
                    center.clock?.let { Text(text = it, color = Color.White, fontSize = 15.sp) }
                }
                TeamScore(center.right, Modifier.weight(1f))
                TeamLogo(center.right.logoUrl, 52.dp)
            }
            if (center.periodLabels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))
                Spacer(modifier = Modifier.height(10.dp))
                val labelSize: TextUnit = if (center.periodLabels.size > 6) 11.sp else 13.sp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(36.dp))
                    center.periodLabels.forEach { label ->
                        Text(label, color = Dim, fontSize = labelSize, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
                listOf(center.left, center.right).forEach { team ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.width(36.dp)) { TeamLogo(team.logoUrl, 24.dp) }
                        center.periodLabels.indices.forEach { i ->
                            Text(
                                text = team.periodScores.getOrNull(i) ?: "-",
                                color = Color.White,
                                fontSize = labelSize,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamScore(team: GameCenterTeam, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(team.abbreviation, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(team.score ?: "-", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun TeamStatsCard(center: GameCenter) {
    val leftColor = teamColor(center.left.color, Color(0xFF6E5BFF))
    PanelCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(center.left.logoUrl, 32.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(center.left.abbreviation, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                Text(center.right.abbreviation, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(10.dp))
                TeamLogo(center.right.logoUrl, 32.dp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))
            center.teamStats.forEach { stat ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stat.leftValue, color = Dim, fontSize = 14.sp, modifier = Modifier.width(56.dp))
                    Text(
                        stat.label,
                        color = Color.White,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(stat.rightValue, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(stat.leftShare.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(leftColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun LeaderCard(leader: GameCenterLeader) {
    val ring = teamColor(leader.teamColor, Color(0xFF3A3A40))
    PanelCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(ring.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!leader.headshotUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = leader.headshotUrl,
                            contentDescription = leader.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp).clip(CircleShape)
                        )
                    } else {
                        Text(leader.name.take(1), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(leader.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${leader.teamName} ${leader.category}", color = Dim, fontSize = 13.sp, maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                leader.columns.forEach { (label, value) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(label, color = Dim, fontSize = 12.sp, maxLines = 1)
                        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScoreCard(box: GameCenterBoxScore) {
    PanelCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(box.team.logoUrl, 40.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(box.team.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    box.team.record?.let { Text(it, color = Dim, fontSize = 13.sp) }
                }
            }
            box.sections.forEach { section ->
                Spacer(modifier = Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#", color = Dim, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                    Text(section.title, color = Dim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    section.columns.forEach { column ->
                        Text(column, color = Dim, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(40.dp))
                    }
                }
                section.rows.forEach { row ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.jersey.orEmpty(), color = Dim, fontSize = 13.sp, modifier = Modifier.width(26.dp))
                        Text(
                            row.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        row.values.forEach { value ->
                            Text(value, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(40.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- Key plays

private fun LazyListScope.playsItems(state: GameCenterUiState, entry: FocusRequester) {
    val plays = state.center?.keyPlays.orEmpty()
    if (plays.isEmpty()) {
        message(
            when {
                state.isLoading && state.center == null -> R.string.game_center_loading
                state.center == null -> R.string.game_center_no_stats
                else -> R.string.game_center_no_plays
            }
        )
        return
    }
    itemsIndexed(plays, key = { i, play -> "play_${plays.size - i}_${play.text.hashCode()}" }) { index, play ->
        PlayCard(play, Modifier.entryIf(index == 0, entry))
    }
}

@Composable
private fun PlayCard(play: GameCenterPlay, modifier: Modifier) {
    PanelCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            TeamLogo(play.teamLogoUrl, 34.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                play.title?.let {
                    Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text(play.text, color = Color.White, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val footer = listOfNotNull(play.time, play.score).joinToString("  ·  ")
                if (footer.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(footer, color = Dim, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- Helpers

@Composable
private fun TeamLogo(url: String?, size: androidx.compose.ui.unit.Dp) {
    if (url.isNullOrBlank()) {
        Spacer(modifier = Modifier.size(size))
        return
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size)
    )
}

/** "4F2683" → the team's color, lightened when it's too dark to see on the panel. */
private fun teamColor(hex: String?, fallback: Color): Color {
    val value = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return fallback
    val color = Color(0xFF000000 or value)
    return if (color.luminance() < 0.08f) lerp(color, Color.White, 0.35f) else color
}
