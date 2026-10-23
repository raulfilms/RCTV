@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.ui.theme.NuvioTheme

/*
 * RCTV: the Glass look of Settings (like ARVIO's): a dark rounded side panel grouped by section
 * (Profile, Playback, Sources, System), the section's title and description over its options, and
 * each option its own soft see-through card, with its value in an outlined pill or a switch on the
 * right. Focus is a thin white line with a faint glow. Only Settings looks like this; the rest of the
 * app keeps its own style.
 */

/** Settings is in the Glass style. */
@Composable
@ReadOnlyComposable
internal fun isGlassSettingsStyle(): Boolean = NuvioTheme.settingsUiStyle == SettingsUiStyle.GLASS

internal val SettingsGlassRowRadius: Dp = 12.dp
internal val SettingsGlassRowShape = RoundedCornerShape(SettingsGlassRowRadius)
internal val SettingsGlassPanelShape = RoundedCornerShape(16.dp)

/** An option's card at rest and focused. */
internal val SettingsGlassRowFill = Color.White.copy(alpha = 0.05f)
internal val SettingsGlassRowFocusedFill = Color.White.copy(alpha = 0.10f)

/** The side panel: near black, a hairline around it. */
internal val SettingsGlassPanelFill = Color(0xFF050505).copy(alpha = 0.88f)
internal val SettingsGlassPanelBorder = Color.White.copy(alpha = 0.07f)

/** The switch when on. */
internal val SettingsGlassSwitchOn = Color(0xFF00D588)

/** The selected section's marks (its icon, the bar at its end). */
internal val SettingsGlassAccent = Color.White

/** An option's card colors: see-through, a little lighter while focused. */
@Composable
internal fun settingsGlassCardColors(): CardColors = CardDefaults.colors(
    containerColor = SettingsGlassRowFill,
    focusedContainerColor = SettingsGlassRowFocusedFill,
    pressedContainerColor = SettingsGlassRowFocusedFill
)

/** No border of the card's own: the focus line is drawn by [settingsGlassFocus]. */
@Composable
internal fun settingsGlassCardBorder(): CardBorder = CardDefaults.border(
    border = Border.None,
    focusedBorder = Border.None,
    pressedBorder = Border.None
)

@Composable
internal fun settingsGlassCardShape(): CardShape = CardDefaults.shape(SettingsGlassRowShape)

/**
 * The Glass focus: a thin white line around the card with a faint glow outside it (drawn over it,
 * so no blur and no delay).
 */
internal fun Modifier.settingsGlassFocus(isFocused: Boolean, cornerRadius: Dp = SettingsGlassRowRadius): Modifier =
    if (!isFocused) this else drawWithCache {
        val inset = 0.75.dp.toPx()
        val origin = Offset(inset, inset)
        val bounds = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f))
        val radius = CornerRadius((cornerRadius.toPx() - inset).coerceAtLeast(0f))
        val outerHalo = Stroke(6.dp.toPx())
        val innerHalo = Stroke(3.dp.toPx())
        val outline = Stroke(1.5.dp.toPx())
        onDrawWithContent {
            drawContent()
            drawRoundRect(Color.White.copy(alpha = 0.035f), origin, bounds, radius, style = outerHalo)
            drawRoundRect(Color.White.copy(alpha = 0.07f), origin, bounds, radius, style = innerHalo)
            drawRoundRect(Color.White.copy(alpha = 0.9f), origin, bounds, radius, style = outline)
        }
    }

/** The Glass switch: green with the knob at its end when on, gray when off. */
@Composable
internal fun SettingsGlassSwitch(checked: Boolean, enabled: Boolean = true, focused: Boolean = false) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(24.dp)
            .background(
                color = if (checked) SettingsGlassSwitchOn.copy(alpha = alpha)
                else Color.White.copy(alpha = (if (focused) 0.3f else 0.2f) * alpha),
                shape = RoundedCornerShape(13.dp)
            )
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(Color.White.copy(alpha = alpha), CircleShape)
        )
    }
}

/** An option's value in an outlined pill ("AUTOMATIC", "MEDIUM", "CONNECT"), brighter while focused. */
@Composable
internal fun SettingsGlassValuePill(
    text: String,
    focused: Boolean,
    enabled: Boolean = true,
    color: Color = Color.White
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .widthIn(max = 260.dp)
            .background(Color.White.copy(alpha = (if (focused) 0.14f else 0.06f) * alpha), RoundedCornerShape(999.dp))
            .border(
                width = 1.5.dp,
                color = Color.White.copy(alpha = (if (focused) 0.85f else 0.45f) * alpha),
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = color.copy(alpha = (if (focused) 1f else 0.88f) * alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A section group's name over its sections in the side panel ("PROFILE", "PLAYBACK"...). */
@Composable
internal fun SettingsGlassGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.6f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 10.dp, top = 10.dp, bottom = 4.dp)
    )
}

/**
 * A section in the side panel: its icon in a little rounded square, its name, and a short bar at the
 * end while it's the one shown.
 */
@Composable
internal fun SettingsGlassRailItemContent(
    title: String,
    icon: ImageVector?,
    rawIconRes: Int?,
    isSelected: Boolean,
    isFocused: Boolean
) {
    val lit = isSelected || isFocused
    val textColor = if (lit) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    if (lit) SettingsGlassAccent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.05f),
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            val tint = if (lit) SettingsGlassAccent else textColor
            if (rawIconRes != null) {
                Image(
                    painter = rememberRawSvgPainter(rawIconRes),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(tint)
                )
            } else if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .background(SettingsGlassAccent, RoundedCornerShape(99.dp))
            )
        }
    }
}
