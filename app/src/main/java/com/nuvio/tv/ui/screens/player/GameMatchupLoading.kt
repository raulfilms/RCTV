package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.SportsEvent

/**
 * RCTV (owner's rule): a game opened from Sports loads with the two teams' badges on black, each
 * side lit with its team's color in diagonal streaks (left team's color on the left), not the
 * channel's logo.
 */
data class GameMatchupArt(
    val leftBadge: String?,
    val leftColor: Color,
    val rightBadge: String?,
    val rightColor: Color,
    /** RCTV (game center): the game's id (its stats beside the player). */
    val eventId: String? = null,
    /** RCTV: the game itself (its channel in another language, for La Liga and the Champions League). */
    val event: com.nuvio.tv.domain.model.SportsEvent? = null
) {
    companion object {
        /** Soccer reads home vs away; the US sports away @ home. */
        fun of(event: SportsEvent): GameMatchupArt {
            val soccer = event.sportName?.contains("soccer", ignoreCase = true) == true
            val home = event.homeTeamBadgeUrl to colorOf(event.homeTeamColor, HOME_FALLBACK)
            val away = event.awayTeamBadgeUrl to colorOf(event.awayTeamColor, AWAY_FALLBACK)
            val (left, right) = if (soccer) home to away else away to home
            return GameMatchupArt(left.first, left.second, right.first, right.second, eventId = event.id, event = event)
        }

        private val HOME_FALLBACK = Color(0xFF1E5BFF)
        private val AWAY_FALLBACK = Color(0xFFE5202E)

        /** "002244" → a color that shows on black (a very dark one is lightened). */
        private fun colorOf(hex: String?, fallback: Color): Color {
            val value = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return fallback
            val color = Color(0xFF000000 or value)
            return if (color.luminance() < 0.08f) lerp(color, Color.White, 0.35f) else color
        }
    }
}

/** The game a player is opening for (set just before it's opened, taken once by the player). */
object LiveGameMatchup {
    @Volatile
    private var pending: Pair<GameMatchupArt, Long>? = null

    fun set(art: GameMatchupArt) {
        pending = art to System.currentTimeMillis()
    }

    /** The game the player was just opened for; null when none (or it's old). */
    fun take(): GameMatchupArt? {
        val (art, at) = pending ?: return null
        pending = null
        return art.takeIf { System.currentTimeMillis() - at < FRESH_MS }
    }

    private const val FRESH_MS = 60_000L
}

@Composable
fun GameMatchupLoadingArt(art: GameMatchupArt, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "matchupPulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse),
        label = "matchupScale"
    )
    val glow = remember(art) {
        Brush.horizontalGradient(
            0f to art.leftColor.copy(alpha = 0.28f),
            0.42f to Color.Transparent,
            0.58f to Color.Transparent,
            1f to art.rightColor.copy(alpha = 0.28f)
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07080A))
    ) {
        Box(modifier = Modifier.fillMaxSize().background(glow))
        // The diagonal streaks: the left team's color on the left, the right team's on the right.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val h = size.height
            val w = size.width
            val slant = h * 0.62f
            val streaks = listOf(
                Triple(0.02f, 14.dp.toPx(), 0.55f),
                Triple(0.07f, 4.dp.toPx(), 0.9f),
                Triple(0.11f, 22.dp.toPx(), 0.22f),
                Triple(0.16f, 3.dp.toPx(), 0.7f),
                Triple(0.21f, 9.dp.toPx(), 0.35f)
            )
            streaks.forEach { (at, width, alpha) ->
                listOf(art.leftColor to at * w - slant * 0.5f, art.rightColor to w - at * w - slant * 0.5f).forEach { (color, x) ->
                    // A soft glow under a sharp line, fading toward the top.
                    val brush = Brush.verticalGradient(
                        0f to color.copy(alpha = alpha * 0.15f),
                        1f to color.copy(alpha = alpha)
                    )
                    drawLine(brush, Offset(x, h), Offset(x + slant, 0f), strokeWidth = width * 3f, cap = StrokeCap.Butt, alpha = 0.18f)
                    drawLine(brush, Offset(x, h), Offset(x + slant, 0f), strokeWidth = width, cap = StrokeCap.Butt)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .fillMaxHeight()
                .align(Alignment.CenterStart),
            contentAlignment = Alignment.Center
        ) {
            Badge(art.leftBadge, pulse)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .fillMaxHeight()
                .align(Alignment.CenterEnd),
            contentAlignment = Alignment.Center
        ) {
            Badge(art.rightBadge, pulse)
        }
    }
}

@Composable
private fun Badge(url: String?, scale: Float) {
    if (url.isNullOrBlank()) return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(230.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    )
}
