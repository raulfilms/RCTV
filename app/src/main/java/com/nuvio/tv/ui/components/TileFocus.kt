package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults

/*
 * RCTV: the outline every tile in the app has while it's focused (and while it's pressed), the
 * one Prime Video draws: a thin white ring just outside the tile, with a hairline of dark between
 * the ring and the artwork, its corners following the tile's. The same on every card, poster, hub
 * tile, channel and game, whatever the theme's colors. Every tile takes it from here, so it stays
 * the same everywhere.
 */

/** Width of a focused tile's ring. */
val TileFocusOutlineWidth: Dp = 2.dp

/** The dark space between the tile's edge and its ring. */
val TileFocusOutlineGap: Dp = 1.dp

/** Color of a focused tile's ring. */
val TileFocusOutlineColor: Color = Color.White

/** A focused tile's ring as a stroke (for tiles that draw their own). */
val TileFocusStroke: BorderStroke = BorderStroke(TileFocusOutlineWidth, TileFocusOutlineColor)

/**
 * How far out the ring's middle is from the tile's edge (TV Material's [Border] inset: positive is
 * outward, its stroke centered there): the gap, then the ring.
 */
private val TileFocusRingOffset: Dp = TileFocusOutlineGap + TileFocusOutlineWidth / 2

/** A focused tile's ring around [shape]. */
fun tileFocusBorder(shape: Shape): Border =
    Border(border = TileFocusStroke, inset = TileFocusRingOffset, shape = ConcentricRingShape(shape, TileFocusRingOffset))

/** For a tile that is a TV [androidx.tv.material3.Card]: no ring at rest, the white ring while focused or pressed. */
@Composable
fun tileCardBorder(shape: Shape): CardBorder = CardDefaults.border(
    border = Border.None,
    focusedBorder = tileFocusBorder(shape),
    pressedBorder = tileFocusBorder(shape)
)

/** For a tile that is a clickable TV [androidx.tv.material3.Surface]: the white ring while focused or pressed. */
@Composable
fun tileSurfaceBorder(shape: Shape): ClickableSurfaceBorder = ClickableSurfaceDefaults.border(
    border = Border.None,
    focusedBorder = tileFocusBorder(shape),
    pressedBorder = tileFocusBorder(shape)
)

/**
 * For a tile that follows its own focus ([focused]): the white ring while it has focus, drawn
 * outside the tile. Put it before the tile's own clip (a clip would cut the ring off).
 */
fun Modifier.tileFocusOutline(focused: Boolean, shape: Shape): Modifier =
    if (!focused) this else this.drawWithContent {
        drawContent()
        val offset = TileFocusRingOffset.toPx()
        inset(-offset) {
            val outline = ConcentricRingShape(shape, TileFocusRingOffset).createOutline(size, layoutDirection, this)
            drawOutline(outline, TileFocusOutlineColor, style = Stroke(width = TileFocusOutlineWidth.toPx()))
        }
    }

/**
 * [base] grown by [grow] all round with its corners grown as much, so the ring's corners follow
 * the tile's (a plain larger copy would look pinched at the corners).
 */
private class ConcentricRingShape(private val base: Shape, private val grow: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val corners = base as? CornerBasedShape ?: return base.createOutline(size, layoutDirection, density)
        val growPx = with(density) { grow.toPx() }
        val tile = Size((size.width - 2 * growPx).coerceAtLeast(0f), (size.height - 2 * growPx).coerceAtLeast(0f))
        fun grown(corner: CornerSize): CornerSize {
            val px = corner.toPx(tile, density)
            return CornerSize(if (px > 0f) px + growPx else 0f)
        }
        return RoundedCornerShape(
            topStart = grown(corners.topStart),
            topEnd = grown(corners.topEnd),
            bottomEnd = grown(corners.bottomEnd),
            bottomStart = grown(corners.bottomStart)
        ).createOutline(size, layoutDirection, density)
    }

    override fun equals(other: Any?): Boolean =
        other is ConcentricRingShape && other.base == base && other.grow == grow

    override fun hashCode(): Int = 31 * base.hashCode() + grow.hashCode()
}
