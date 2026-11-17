package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.GameCenterBoxSection
import com.nuvio.tv.domain.model.SportsGameStat

/*
 * RCTV (owner): every game's page, in every sport, in this look: frosted glass boxes on a dark
 * night-blue background lit by soft blue and purple glows. The game on top (logos, VS or the score,
 * when and where, Watch Recap), its buttons as glass pills, then Team Comparison and each team's
 * players (offense, defense...), each a box showing its first lines; choosing a box opens it whole
 * (its lines can then be gone through with the remote) and choosing it again closes it.
 */

internal val GlassFill = Color.White.copy(alpha = 0.06f)
internal val GlassBorder = Color.White.copy(alpha = 0.16f)
internal val GlassDim = Color.White.copy(alpha = 0.62f)
private val BarTrack = Color.White.copy(alpha = 0.12f)
private val BarFill = Color.White.copy(alpha = 0.72f)
private val PanelShape = RoundedCornerShape(22.dp)

/** The page's background: night blue, with a blue glow low on the left and a purple one low on the right. */
internal fun Modifier.gameGlassBackground(): Modifier = this
    .background(Brush.verticalGradient(listOf(Color(0xFF0B1022), Color(0xFF0E1530), Color(0xFF0A0F22))))
    .drawBehind {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF2C5BD6).copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * 0.12f, size.height * 0.62f),
                radius = size.width * 0.32f
            ),
            radius = size.width * 0.32f,
            center = Offset(size.width * 0.12f, size.height * 0.62f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF8B3FD6).copy(alpha = 0.5f), Color.Transparent),
                center = Offset(size.width * 0.9f, size.height * 0.85f),
                radius = size.width * 0.3f
            ),
            radius = size.width * 0.3f,
            center = Offset(size.width * 0.9f, size.height * 0.85f)
        )
    }

/** A frosted glass box. */
internal fun Modifier.glassPanel(highlight: Boolean = false): Modifier = this
    .clip(PanelShape)
    .background(GlassFill)
    .border(
        width = if (highlight) 2.dp else 1.dp,
        color = if (highlight) Color.White else GlassBorder,
        shape = PanelShape
    )

/** A glass pill (Add to library, Add a team to My Teams): white with dark words when focused. */
@Composable
internal fun GlassPillButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF0B1022)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, GlassBorder), shape = shape),
            focusedBorder = Border(border = BorderStroke(1.dp, Color.White), shape = shape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Watch Recap: a glowing blue glass pill, centered under the game. */
@Composable
internal fun GlassRecapButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier.height(50.dp).width(260.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF2C5BD6).copy(alpha = 0.55f),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF3A6CF0),
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, Color(0xFF9DB8FF).copy(alpha = 0.6f)), shape = shape),
            focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = shape)
        ),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow(elevationColor = Color(0xFF3A6CF0).copy(alpha = 0.45f), elevation = 14.dp),
            focusedGlow = Glow(elevationColor = Color(0xFF3A6CF0).copy(alpha = 0.75f), elevation = 22.dp)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.game_detail_watch_recap).uppercase(),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * A box that shows its first [collapsedRows] lines; choosing its title opens it whole (and closes
 * it again). Open, its lines take the remote's focus one by one, so a long box can be gone through.
 */
@Composable
internal fun GlassExpandablePanel(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    rowCount: Int,
    modifier: Modifier = Modifier,
    collapsedRows: Int = 3,
    leading: (@Composable () -> Unit)? = null,
    subHeader: (@Composable () -> Unit)? = null,
    row: @Composable (index: Int) -> Unit
) {
    var headerFocused by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().glassPanel(highlight = headerFocused)) {
        Surface(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().onFocusChanged { headerFocused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = Color.White.copy(alpha = 0.06f),
                focusedContentColor = Color.White
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 16.dp, bottom = 6.dp)
            ) {
                leading?.let {
                    it()
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = title,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (rowCount > collapsedRows) {
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.game_detail_show_less)
                        } else {
                            stringResource(R.string.game_detail_show_all, rowCount)
                        },
                        color = GlassDim,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = GlassDim,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        subHeader?.invoke()
        val shown = if (expanded) rowCount else minOf(rowCount, collapsedRows)
        for (index in 0 until shown) {
            GlassPanelRow(focusable = expanded) { row(index) }
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** A line of an open box: it takes the remote's focus (lighter then), so the page scrolls with it. */
@Composable
private fun GlassPanelRow(focusable: Boolean, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .then(if (focusable) Modifier.onFocusChanged { focused = it.isFocused }.focusable() else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        content()
    }
}

/** Team Comparison's line: each side's value, the stat between them, and two bars meeting in the middle. */
@Composable
internal fun ComparisonLine(stat: SportsGameStat) {
    val left = leadingNumber(stat.homeValue)
    val right = leadingNumber(stat.awayValue)
    val total = left + right
    val leftShare = if (total > 0f) left / total else 0.5f
    val rightShare = if (total > 0f) right / total else 0.5f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stat.homeValue,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1
            )
            Text(
                text = stat.label,
                fontSize = 15.sp,
                color = GlassDim,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.4f).padding(bottom = 2.dp)
            )
            Text(
                text = stat.awayValue,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            // The left team's bar grows from the middle to the left; the right team's to the right.
            Box(modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(BarTrack)) {
                Box(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(leftShare.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(2.dp)).background(BarFill)
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(BarTrack)) {
                Box(
                    modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(rightShare.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(2.dp)).background(BarFill)
                )
            }
        }
    }
}

/** The teams' names over Team Comparison's sides. */
@Composable
internal fun ComparisonTeams(left: String, right: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)) {
        Text(left, color = GlassDim, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
        Text(right, color = GlassDim, fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1)
    }
}

/** The first number in a stat ("44-88" → 44, "50.0%" → 50, "31:42" → 31). */
private fun leadingNumber(value: String): Float =
    Regex("\\d+(?:\\.\\d+)?").find(value)?.value?.toFloatOrNull() ?: 0f

private val PlayerColumnWidth = 46.dp
private val PlayerNameWidth = 210.dp

/** A players' box's column names ("PLAYER", "MIN", "PTS"...), as many as fit. */
@Composable
internal fun BoxSectionHeader(section: GameCenterBoxSection) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)) {
        val fits = columnsThatFit(maxWidth.value, section.columns.size)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.game_detail_player),
                color = GlassDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(PlayerNameWidth)
            )
            Spacer(Modifier.weight(1f))
            section.columns.take(fits).forEach { column ->
                Text(
                    text = column,
                    color = GlassDim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(PlayerColumnWidth)
                )
            }
        }
    }
}

/** A player's line in a players' box: number and name, then their numbers. */
@Composable
internal fun BoxSectionLine(section: GameCenterBoxSection, index: Int) {
    val row = section.rows[index]
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // As wide as the header's room (the line's own padding matches it), so the columns line up.
        val fits = columnsThatFit(maxWidth.value, section.columns.size)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = listOfNotNull(row.jersey?.takeIf { it.isNotBlank() }?.let { "#$it" }, row.name).joinToString("  "),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(PlayerNameWidth)
            )
            Spacer(Modifier.weight(1f))
            row.values.take(fits).forEach { value ->
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(PlayerColumnWidth)
                )
            }
        }
    }
}

private fun columnsThatFit(widthDp: Float, columns: Int): Int =
    ((widthDp - PlayerNameWidth.value) / PlayerColumnWidth.value).toInt().coerceIn(0, columns)

/** A small round space for the team's logo in its players' boxes. */
@Composable
internal fun GlassTeamLogo(url: String?) {
    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        SportsLogoImage(url = url, background = SportsLogoDarkBackground, contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

/** Space between the page's glass boxes and the page's sides. */
internal val GlassPageSide = 40.dp
