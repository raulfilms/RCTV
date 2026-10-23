package com.nuvio.tv.ui.screens.player

import android.graphics.Typeface
import com.nuvio.tv.data.local.SubtitleEdge
import com.nuvio.tv.data.local.SubtitleFont

/*
 * RCTV: the subtitle fonts and edges of Subtitle Styling, as each player draws them. The fonts are
 * Android's own caption fonts (the ones TV captions name: casual, cursive, monospace...).
 */

/** The typeface for [font] ([bold] or not), from the fonts every Android TV has. */
internal fun subtitleTypeface(font: SubtitleFont, bold: Boolean): Typeface {
    val style = if (bold) Typeface.BOLD else Typeface.NORMAL
    val family = font.androidFamily() ?: return Typeface.create(Typeface.DEFAULT, style)
    return Typeface.create(family, style)
}

/** Android's family name for [this] font (null: the default one). */
internal fun SubtitleFont.androidFamily(): String? = when (this) {
    SubtitleFont.DEFAULT -> null
    SubtitleFont.CASUAL -> "casual"
    SubtitleFont.CURSIVE -> "cursive"
    SubtitleFont.MONOSPACE_SANS -> "monospace"
    SubtitleFont.MONOSPACE_SERIF -> "serif-monospace"
    SubtitleFont.PROPORTIONAL_SANS -> "sans-serif"
    SubtitleFont.PROPORTIONAL_SERIF -> "serif"
    SubtitleFont.SMALL_CAPS -> "sans-serif-smallcaps"
}

/** The same fonts by their own names, for mpv (libass finds the device's fonts by name). */
internal fun SubtitleFont.mpvFamily(): String = when (this) {
    SubtitleFont.DEFAULT, SubtitleFont.PROPORTIONAL_SANS -> "Roboto"
    SubtitleFont.CASUAL -> "Coming Soon"
    SubtitleFont.CURSIVE -> "Dancing Script"
    SubtitleFont.MONOSPACE_SANS -> "Droid Sans Mono"
    SubtitleFont.MONOSPACE_SERIF -> "Cutive Mono"
    SubtitleFont.PROPORTIONAL_SERIF -> "Noto Serif"
    SubtitleFont.SMALL_CAPS -> "Carrois Gothic SC"
}

/** ExoPlayer's caption edge for [this] edge. */
internal fun SubtitleEdge.captionEdgeType(): Int = when (this) {
    SubtitleEdge.NONE -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
    SubtitleEdge.OUTLINE -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
    SubtitleEdge.DROP_SHADOW -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
    SubtitleEdge.RAISED -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_RAISED
    SubtitleEdge.DEPRESSED -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DEPRESSED
}
