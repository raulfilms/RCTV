@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SubtitleEdge
import com.nuvio.tv.data.local.SubtitleFont
import com.nuvio.tv.data.local.SubtitleStyleSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * RCTV: Subtitle Styling, as on Disney+: a list on the left (Font, Font Color, Font Opacity, Font Size,
 * Font Edge, Background Opacity and Color, Window Opacity and Color, and Position), each changed with
 * Left/Right (or Select, to the next), and how subtitles will look shown on the right as they change.
 * Done and Reset to Default below.
 */

/** The caption colors TV captions offer (RGB). */
private val CAPTION_COLORS = listOf(
    0xFFFFFF to R.string.styling_color_white,
    0x000000 to R.string.styling_color_black,
    0xFF0000 to R.string.styling_color_red,
    0x00FF00 to R.string.styling_color_green,
    0x0000FF to R.string.styling_color_blue,
    0xFFFF00 to R.string.styling_color_yellow,
    0xFF00FF to R.string.styling_color_magenta,
    0x00FFFF to R.string.styling_color_cyan
)
private val FONT_OPACITIES = listOf(25, 50, 75, 100)
private val BOX_OPACITIES = listOf(0, 25, 50, 75, 100)
private val FONT_SIZES = listOf(50, 75, 100, 150, 200)
private val POSITIONS = listOf(-20, -10, 0, 5, 10, 20, 30, 40, 50)

/** One line of the list: its name, what it's set to, and the change Left/Right make (-1 / +1). */
private class StylingRow(val label: String, val value: String, val step: (Int) -> Unit)

@Composable
internal fun SubtitleStylingPanel(
    subtitleStyle: SubtitleStyleSettings,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** The subtitle on keeps its own style (ASS with libass): only Done works. */
    isStyleDisabledByLibass: Boolean = false
) {
    val style = subtitleStyle
    val rows = stylingRows(style, onEvent)
    val firstRow = remember { FocusRequester() }
    val doneButton = remember { FocusRequester() }
    var focusedIndex by remember { mutableStateOf(0) }
    LaunchedEffect(isStyleDisabledByLibass) {
        repeat(8) {
            withFrameNanos { }
            val target = if (isStyleDisabledByLibass) doneButton else firstRow
            if (runCatching { target.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        // The list.
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(470.dp)
                .background(Color(0xF0151A24))
                .padding(start = 36.dp, end = 28.dp, top = 28.dp, bottom = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.styling_title).uppercase(),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            if (isStyleDisabledByLibass) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.styling_libass_note),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier
                    .weight(1f)
                    .then(if (isStyleDisabledByLibass) Modifier.alpha(0.35f).focusProperties { canFocus = false } else Modifier)
            ) {
                itemsIndexed(rows) { index, row ->
                    StylingRowItem(
                        row = row,
                        focused = focusedIndex == index,
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstRow) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedIndex = index }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StylingButton(
                    text = stringResource(R.string.styling_done),
                    primary = true,
                    onClick = { onEvent(PlayerEvent.OnDismissSubtitleStylePanel) },
                    modifier = Modifier.focusRequester(doneButton)
                )
                StylingButton(
                    text = stringResource(R.string.styling_reset),
                    primary = false,
                    enabled = !isStyleDisabledByLibass,
                    onClick = { onEvent(PlayerEvent.OnResetSubtitleDefaults) }
                )
            }
        }

        // How subtitles will look.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            SubtitleStylePreview(style = style)
        }
    }
}

@Composable
private fun stylingRows(style: SubtitleStyleSettings, onEvent: (PlayerEvent) -> Unit): List<StylingRow> {
    val textRgb = style.textColor and 0xFFFFFF
    val backgroundRgb = style.backgroundColor and 0xFFFFFF
    val windowRgb = style.windowColor and 0xFFFFFF
    val textOpacity = opacityOf(style.textColor)
    val backgroundOpacity = opacityOf(style.backgroundColor)
    val windowOpacity = opacityOf(style.windowColor)
    val on = stringResource(R.string.styling_on)
    val off = stringResource(R.string.styling_off)
    val fontNames = SubtitleFont.entries.associateWith { stringResource(it.labelRes()) }
    val edgeNames = SubtitleEdge.entries.associateWith { stringResource(it.labelRes()) }
    val colorNames = CAPTION_COLORS.associate { (rgb, label) -> rgb to stringResource(label) }
    val custom = stringResource(R.string.styling_color_custom)
    fun colorName(rgb: Int) = colorNames[rgb] ?: custom

    return listOf(
        StylingRow(stringResource(R.string.styling_font), fontNames.getValue(style.font)) { d ->
            onEvent(PlayerEvent.OnSetSubtitleFont(SubtitleFont.entries.cycle(style.font, d)))
        },
        StylingRow(stringResource(R.string.styling_bold), if (style.bold) on else off) {
            onEvent(PlayerEvent.OnSetSubtitleBold(!style.bold))
        },
        StylingRow(stringResource(R.string.styling_font_color), colorName(textRgb)) { d ->
            onEvent(PlayerEvent.OnSetSubtitleTextColor(withAlpha(nextColor(textRgb, d), textOpacity)))
        },
        StylingRow(stringResource(R.string.styling_font_opacity), "$textOpacity%") { d ->
            onEvent(PlayerEvent.OnSetSubtitleTextColor(withAlpha(textRgb, FONT_OPACITIES.cycleNearest(textOpacity, d))))
        },
        StylingRow(stringResource(R.string.styling_font_size), "${style.size}%") { d ->
            onEvent(PlayerEvent.OnSetSubtitleSize(FONT_SIZES.cycleNearest(style.size, d)))
        },
        StylingRow(stringResource(R.string.styling_font_edge), edgeNames.getValue(style.edge)) { d ->
            onEvent(PlayerEvent.OnSetSubtitleEdge(SubtitleEdge.entries.cycle(style.edge, d)))
        },
        StylingRow(stringResource(R.string.styling_background_opacity), "$backgroundOpacity%") { d ->
            onEvent(PlayerEvent.OnSetSubtitleBackgroundColor(withAlpha(backgroundRgb, BOX_OPACITIES.cycleNearest(backgroundOpacity, d))))
        },
        StylingRow(stringResource(R.string.styling_background_color), colorName(backgroundRgb)) { d ->
            onEvent(PlayerEvent.OnSetSubtitleBackgroundColor(withAlpha(nextColor(backgroundRgb, d), backgroundOpacity)))
        },
        StylingRow(stringResource(R.string.styling_window_opacity), "$windowOpacity%") { d ->
            onEvent(PlayerEvent.OnSetSubtitleWindowColor(withAlpha(windowRgb, BOX_OPACITIES.cycleNearest(windowOpacity, d))))
        },
        StylingRow(stringResource(R.string.styling_window_color), colorName(windowRgb)) { d ->
            onEvent(PlayerEvent.OnSetSubtitleWindowColor(withAlpha(nextColor(windowRgb, d), windowOpacity)))
        },
        StylingRow(stringResource(R.string.styling_position), positionLabel(style.verticalOffset)) { d ->
            onEvent(PlayerEvent.OnSetSubtitleVerticalOffset(POSITIONS.cycleNearest(style.verticalOffset, d, wrap = false)))
        }
    )
}

private fun positionLabel(offset: Int): String = when {
    offset > 0 -> "+$offset"
    else -> offset.toString()
}

/** A color's opacity in percent (rounded to 5). */
private fun opacityOf(argb: Int): Int = (((argb ushr 24) and 0xFF) * 100 / 255.0 / 5).roundToInt() * 5

private fun withAlpha(rgb: Int, opacityPercent: Int): Int =
    ((opacityPercent.coerceIn(0, 100) * 255 / 100.0).roundToInt() shl 24) or (rgb and 0xFFFFFF)

/** The caption color after (or before) [rgb] in the list; a color not in it goes to the first. */
private fun nextColor(rgb: Int, direction: Int): Int {
    val index = CAPTION_COLORS.indexOfFirst { it.first == rgb }
    if (index < 0) return CAPTION_COLORS.first().first
    return CAPTION_COLORS[Math.floorMod(index + direction, CAPTION_COLORS.size)].first
}

private fun <T> List<T>.cycle(current: T, direction: Int): T {
    val index = indexOf(current).coerceAtLeast(0)
    return this[Math.floorMod(index + direction, size)]
}

/** The value after (or before) the one nearest [current] ([wrap]: from the last back to the first). */
private fun List<Int>.cycleNearest(current: Int, direction: Int, wrap: Boolean = true): Int {
    val nearest = indices.minByOrNull { abs(this[it] - current) } ?: 0
    val index = if (this[nearest] == current) nearest + direction else {
        // Between two values: the next one that way.
        if (direction > 0) indexOfFirst { it > current }.let { if (it < 0) size else it }
        else indexOfLast { it < current }
    }
    return when {
        wrap -> this[Math.floorMod(index, size)]
        else -> this[index.coerceIn(0, size - 1)]
    }
}

private fun SubtitleFont.labelRes(): Int = when (this) {
    SubtitleFont.DEFAULT -> R.string.styling_font_default
    SubtitleFont.CASUAL -> R.string.styling_font_casual
    SubtitleFont.CURSIVE -> R.string.styling_font_cursive
    SubtitleFont.MONOSPACE_SANS -> R.string.styling_font_monospace_sans
    SubtitleFont.MONOSPACE_SERIF -> R.string.styling_font_monospace_serif
    SubtitleFont.PROPORTIONAL_SANS -> R.string.styling_font_proportional_sans
    SubtitleFont.PROPORTIONAL_SERIF -> R.string.styling_font_proportional_serif
    SubtitleFont.SMALL_CAPS -> R.string.styling_font_small_caps
}

private fun SubtitleEdge.labelRes(): Int = when (this) {
    SubtitleEdge.NONE -> R.string.styling_edge_none
    SubtitleEdge.OUTLINE -> R.string.styling_edge_uniform
    SubtitleEdge.DROP_SHADOW -> R.string.styling_edge_drop_shadow
    SubtitleEdge.RAISED -> R.string.styling_edge_raised
    SubtitleEdge.DEPRESSED -> R.string.styling_edge_depressed
}

/** A line of the list: its name on the left, its value on the right (with ‹ › while focused). */
@Composable
private fun StylingRowItem(row: StylingRow, focused: Boolean, modifier: Modifier = Modifier) {
    Surface(
        onClick = { row.step(1) },
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        row.step(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        row.step(1)
                        true
                    }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color.White.copy(alpha = 0.14f),
            focusedContentColor = Color.White
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = row.label,
                fontSize = 17.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (focused) "‹  ${row.value}  ›" else row.value,
                fontSize = 17.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun StylingButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color(0xFF1FA4E8) else Color(0xFF5B5FC7),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            disabledContainerColor = Color.White.copy(alpha = 0.08f),
            disabledContentColor = Color.White.copy(alpha = 0.4f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
    ) {
        Box(modifier = Modifier.padding(horizontal = 28.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(text = text.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

/** "Subtitles will appear like this", in the chosen style, over a bright picture (so opacity shows). */
@Composable
private fun SubtitleStylePreview(style: SubtitleStyleSettings) {
    val sample = stringResource(R.string.styling_preview)
    val textColor = Color(style.textColor)
    val backgroundColor = Color(style.backgroundColor)
    val windowColor = Color(style.windowColor)
    val edgeColor = Color(style.outlineColor)
    val fontFamily = FontFamily(subtitleTypeface(style.font, style.bold))
    val fontSize = (20f * style.size / 100f).sp
    val shadow = when (style.edge) {
        SubtitleEdge.DROP_SHADOW -> Shadow(edgeColor, Offset(3f, 3f), 4f)
        SubtitleEdge.RAISED -> Shadow(edgeColor, Offset(2f, 2f), 1f)
        SubtitleEdge.DEPRESSED -> Shadow(edgeColor, Offset(-2f, -2f), 1f)
        else -> null
    }
    val baseStyle = TextStyle(
        fontFamily = fontFamily,
        fontSize = fontSize,
        textAlign = TextAlign.Center,
        shadow = shadow
    )
    Box(
        modifier = Modifier
            .width(520.dp)
            .height(170.dp),
        contentAlignment = Alignment.Center
    ) {
        PreviewBackdrop()
        Box(
            modifier = Modifier
                .background(windowColor)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            // The edge (an outline drawn under the letters), then the letters on their line's box.
            if (style.edge == SubtitleEdge.OUTLINE) {
                Text(
                    text = sample,
                    color = edgeColor,
                    style = baseStyle.copy(
                        shadow = null,
                        drawStyle = Stroke(width = 5f, join = StrokeJoin.Round)
                    )
                )
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(background = backgroundColor)) { append(sample) }
                },
                color = textColor,
                style = baseStyle
            )
        }
    }
}

/** A bright, busy picture behind the preview: sky and clouds. */
@Composable
private fun PreviewBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF4FD8EA), Color(0xFF2BB8D8))))
        val cloud = Color.White.copy(alpha = 0.95f)
        val spots = listOf(
            0.08f to 0.20f, 0.30f to 0.12f, 0.55f to 0.22f, 0.80f to 0.10f, 0.92f to 0.45f,
            0.15f to 0.70f, 0.40f to 0.82f, 0.68f to 0.75f, 0.88f to 0.85f, 0.25f to 0.45f
        )
        spots.forEach { (x, y) ->
            val center = Offset(size.width * x, size.height * y)
            drawOval(cloud, topLeft = center - Offset(18f, 8f), size = Size(36f, 16f))
            drawOval(cloud, topLeft = center - Offset(8f, 16f), size = Size(18f, 16f))
        }
    }
}
