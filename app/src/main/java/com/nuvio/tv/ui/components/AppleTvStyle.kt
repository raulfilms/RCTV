package com.nuvio.tv.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.theme.InterFamily
import com.nuvio.tv.ui.theme.NuvioPrimitives

/*
 * "Apple TV 2026 Style" design sheet (tvOS 26/27, Liquid Glass), applied to RCTV.
 *
 * The sheet is written in tvOS points for a 1920x1080 screen. Android TV lays out
 * at 960x540 dp, so every size here is the sheet's value divided by 2.
 * Official (Apple HIG): text sizes and weights, safe margins, grid gaps and column
 * widths. Approximate (taken from screenshots): colors, glass, radii, shadow, focus scale.
 */

/** Colors from the sheet (tvOS dark system colors). */
object AppleTvColors {
    /** RCTV's app background, #1B1D29: Home, Movies & TV, See All, the Guide and every other page. */
    val Background = NuvioPrimitives.appBackground
    /** Raised solid surface: cards without a poster, list backgrounds (the background + 7% white). */
    val Surface1 = Color(0xFF2B2D38)
    /** Second elevation: rows inside a panel, pop-up menus (the background + 14% white). */
    val Surface2 = Color(0xFF3B3D47)
    /** Liquid Glass: panels, menus and unfocused buttons, over blurred content. */
    val Glass = Color.White.copy(alpha = 0.16f)
    /** Stronger glass: player controls, overlays on bright video. */
    val GlassStrong = Color.White.copy(alpha = 0.26f)
    /**
     * Dark layer laid under [Glass], so white text on the glass keeps 4.5:1 over bright
     * artwork too (the sheet's glass value assumes a dark background).
     */
    val GlassShade = Color.Black.copy(alpha = 0.30f)
    /** [GlassShade] and [Glass] stacked into one color, for surfaces that take a single fill. */
    val GlassOnGray = Color(red = 0x63, green = 0x63, blue = 0x63, alpha = 0x69)
    /** Primary text: titles, row names, unfocused button text. */
    val Label = Color.White
    /** Secondary text on dark surfaces: year, genre, runtime, descriptions. */
    val LabelSecondary = Color(red = 235, green = 235, blue = 245, alpha = 153)
    /** Tertiary and disabled text; large or non-essential text only (under 4.5:1). */
    val LabelTertiary = Color(red = 235, green = 235, blue = 245, alpha = 77)
    /** Fill of a focused button: solid white. */
    val FocusFill = Color.White
    /** Text and icon inside a focused button. */
    val FocusLabel = Color(0xFF000000)
    /** System blue (dark): progress bars, active state. Use sparingly. */
    val Accent = Color(0xFF0A84FF)
    /** System red (dark): destructive actions. */
    val Destructive = Color(0xFFFF453A)
    /** Thin dividers in lists and menus. */
    val Separator = Color(red = 84, green = 84, blue = 88, alpha = 166)
}

/**
 * tvOS text styles (official sizes), in Inter: SF Pro is licensed for Apple platforms only and
 * Inter is its closest match. Medium for almost everything, Regular only for [Subtitle1].
 */
object AppleTvType {
    val Family: FontFamily = InterFamily

    private fun style(size: Float, lineHeight: Float, weight: FontWeight = FontWeight.Medium) = TextStyle(
        fontFamily = Family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp
    )

    /** Title of a movie's page (76 / 96 pt). */
    val Title1: TextStyle = style(38f, 48f)
    /** Page titles (57 / 66 pt). */
    val Title2: TextStyle = style(28.5f, 33f)
    /** 48 / 56 pt. */
    val Title3: TextStyle = style(24f, 28f)
    /** Row names ("Up Next", "Top Chart") (38 / 46 pt). */
    val Headline: TextStyle = style(19f, 23f)
    /** 38 / 46 pt, Regular. */
    val Subtitle1: TextStyle = style(19f, 23f, FontWeight.Normal)
    /** Button text ("Play", "+ Up Next") (31 / 38 pt). */
    val Callout: TextStyle = style(15.5f, 19f)
    /** Default text, synopses (29 / 36 pt). */
    val Body: TextStyle = style(14.5f, 18f)
    /** Title under a poster (25 / 32 pt). */
    val Caption1: TextStyle = style(12.5f, 16f)
    /** Metadata (year · genre), the smallest text (23 / 30 pt). */
    val Caption2: TextStyle = style(11.5f, 15f)
}

/** Spacing and grid sizes from the sheet. */
object AppleTvSpacing {
    /** Between an icon and its text inside a button. */
    val Space1: Dp = 4.dp
    /** Between a poster and its title below. */
    val Space2: Dp = 8.dp
    /** Inner padding of glass panels. */
    val Space3: Dp = 12.dp
    /** Official: horizontal space between items in a row or grid. */
    val GridGap: Dp = 20.dp
    /** Official: safe margin at the top and bottom of the screen. */
    val SafeY: Dp = 30.dp
    /** Official: safe margin at the left and right. */
    val SafeX: Dp = 40.dp
    /** Official: minimum vertical space between unfocused rows of a grid. */
    val RowGap: Dp = 50.dp
    /** Official: item width in a 4-column grid (wide cards). */
    val Col4: Dp = 205.dp
    /** Official: item width in a 5-column grid. */
    val Col5: Dp = 160.dp
    /** Official: item width in a 6-column grid (vertical posters). */
    val Col6: Dp = 130.dp
}

/** Corner radii from the sheet. */
object AppleTvRadius {
    /** Posters and cards. */
    val Poster: Dp = 6.dp
    /** Liquid Glass panels and menus. */
    val Panel: Dp = 14.dp
    /** Text buttons and player controls: capsule. */
    val Pill = RoundedCornerShape(percent = 50)
}

/** Vertical poster size: [AppleTvSpacing.Col6] wide, 2:3. */
val AppleTvPosterWidth: Dp = AppleTvSpacing.Col6
val AppleTvPosterHeight: Dp = AppleTvSpacing.Col6 * 1.5f
