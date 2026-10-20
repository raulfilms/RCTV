package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults

/*
 * RCTV: the outline every tile in the app has while it's focused (and while it's pressed): white,
 * the same width on every card, poster, hub tile, channel and game, whatever the theme's colors.
 * Every tile takes it from here, so it stays the same everywhere.
 */

/** Width of a focused tile's outline. */
val TileFocusOutlineWidth: Dp = 3.dp

/** Color of a focused tile's outline. */
val TileFocusOutlineColor: Color = Color.White

/** A focused tile's outline as a stroke (for tiles that draw their own). */
val TileFocusStroke: BorderStroke = BorderStroke(TileFocusOutlineWidth, TileFocusOutlineColor)

/** A focused tile's outline in [shape]. */
fun tileFocusBorder(shape: Shape): Border = Border(border = TileFocusStroke, shape = shape)

/** For a tile that is a TV [androidx.tv.material3.Card]: no outline at rest, white while focused or pressed. */
@Composable
fun tileCardBorder(shape: Shape): CardBorder = CardDefaults.border(
    border = Border.None,
    focusedBorder = tileFocusBorder(shape),
    pressedBorder = tileFocusBorder(shape)
)

/** For a tile that is a clickable TV [androidx.tv.material3.Surface]: white while focused or pressed. */
@Composable
fun tileSurfaceBorder(shape: Shape): ClickableSurfaceBorder = ClickableSurfaceDefaults.border(
    border = Border.None,
    focusedBorder = tileFocusBorder(shape),
    pressedBorder = tileFocusBorder(shape)
)

/** For a tile that follows its own focus ([focused]): the white outline while it has focus. */
fun Modifier.tileFocusOutline(focused: Boolean, shape: Shape): Modifier =
    if (focused) this.border(TileFocusStroke, shape) else this
