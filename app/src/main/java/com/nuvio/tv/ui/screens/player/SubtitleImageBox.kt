package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput
import com.nuvio.tv.ui.util.withReadablePixels
import kotlin.math.roundToInt

/**
 * RCTV: every subtitle with a black box behind each line, the image ones too (a Blu-ray's PGS, a
 * DVD's VobSub, DVB): their text is a picture, so the box is drawn into the picture, behind each
 * row of letters, with a little room around it, the way text subtitles get it. The picture's own
 * letters (their font, their outline) stay as they are.
 */
internal object SubtitleImageBox {

    /** A pixel this opaque is part of the letters (their soft edges are less). */
    private const val VISIBLE_ALPHA = 24

    /** Rows of letters this close together are one line (an accent over a capital, an i's dot). */
    private const val LINE_GAP_PX = 3

    data class Line(val top: Int, val bottom: Int, val left: Int, val right: Int)

    /** The lines of letters in [pixelsOfRow]'s picture (rows [height], each [width] wide), top first. */
    fun linesOf(width: Int, height: Int, pixelsOfRow: (y: Int, row: IntArray) -> Unit): List<Line> {
        if (width <= 0 || height <= 0) return emptyList()
        val row = IntArray(width)
        val lines = ArrayList<Line>()
        var top = -1
        var bottom = -1
        var left = Int.MAX_VALUE
        var right = -1
        for (y in 0 until height) {
            pixelsOfRow(y, row)
            var first = -1
            var last = -1
            for (x in 0 until width) {
                if ((row[x] ushr 24) > VISIBLE_ALPHA) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            if (top >= 0 && y - bottom > LINE_GAP_PX + 1) {
                lines += Line(top, bottom, left, right)
                top = -1
            }
            if (top < 0) {
                top = y
                left = first
                right = last
            } else {
                if (first < left) left = first
                if (last > right) right = last
            }
            bottom = y
        }
        if (top >= 0) lines += Line(top, bottom, left, right)
        return lines
    }

    /** The room around each line's letters: about a sixth of the tallest line, 3 to 24 pixels. */
    fun paddingFor(lines: List<Line>): Int {
        val tallest = lines.maxOfOrNull { it.bottom - it.top + 1 } ?: return 0
        return (tallest * 0.17f).roundToInt().coerceIn(3, 24)
    }

    /**
     * [cue] with a box of [color] behind each line of its picture; the cue itself when it has no
     * picture, no letters, or anything goes wrong. When the cue says where and how big it is on
     * the screen, the picture grows by the room around the boxes and keeps its letters in place;
     * else the boxes stay within the picture.
     */
    fun boxed(cue: Cue, color: Int): Cue {
        val source = cue.bitmap ?: return cue
        if ((color ushr 24) == 0) return cue
        return runCatching {
            // A hardware picture's pixels can't be read: a readable copy (see ui/util/BitmapPixels.kt).
            val readable = source.withReadablePixels()
            val lines = linesOf(readable.width, readable.height) { y, row -> readable.getPixels(row, 0, readable.width, 0, y, readable.width, 1) }
            if (lines.isEmpty()) return cue
            val pad = paddingFor(lines)
            val placed = cue.size != Cue.DIMEN_UNSET && cue.bitmapHeight != Cue.DIMEN_UNSET &&
                cue.position != Cue.DIMEN_UNSET && cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION
            val grow = if (placed) pad else 0
            val out = Bitmap.createBitmap(readable.width + 2 * grow, readable.height + 2 * grow, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint().apply { this.color = color; isAntiAlias = false }
            lines.forEach { line ->
                canvas.drawRect(
                    (line.left + grow - pad).coerceAtLeast(0).toFloat(),
                    (line.top + grow - pad).coerceAtLeast(0).toFloat(),
                    (line.right + grow + pad + 1).coerceAtMost(out.width).toFloat(),
                    (line.bottom + grow + pad + 1).coerceAtMost(out.height).toFloat(),
                    paint
                )
            }
            canvas.drawBitmap(readable, grow.toFloat(), grow.toFloat(), null)
            if (readable !== source) readable.recycle()
            val builder = cue.buildUpon().setBitmap(out)
            if (grow > 0) {
                // The same screen fraction per pixel as before: the letters stay where they were.
                val perPixelX = cue.size / source.width
                val perPixelY = cue.bitmapHeight / source.height
                builder.setSize(cue.size + 2 * grow * perPixelX)
                builder.setBitmapHeight(cue.bitmapHeight + 2 * grow * perPixelY)
                builder.setPosition(cue.position + shift(cue.positionAnchor) * grow * perPixelX)
                builder.setLine(cue.line + shift(cue.lineAnchor) * grow * perPixelY, Cue.LINE_TYPE_FRACTION)
            }
            builder.build()
        }.getOrDefault(cue)
    }

    /**
     * RCTV: [cue]'s picture [scale] times as big on the screen (the subtitle size the viewer chose,
     * as for text subtitles), its shape kept. A subtitle low on the screen grows up from its bottom
     * edge, one high on it down from its top edge, from its middle sideways; it stays on the screen.
     * The cue itself when it doesn't say where and how big it is.
     */
    fun scaled(cue: Cue, scale: Float): Cue {
        if (cue.bitmap == null || scale <= 0f || scale == 1f) return cue
        if (cue.size == Cue.DIMEN_UNSET || cue.bitmapHeight == Cue.DIMEN_UNSET || cue.position == Cue.DIMEN_UNSET ||
            cue.line == Cue.DIMEN_UNSET || cue.lineType != Cue.LINE_TYPE_FRACTION || cue.size <= 0f || cue.bitmapHeight <= 0f
        ) {
            return cue
        }
        val width = (cue.size * scale).coerceAtMost(1f)
        val height = cue.bitmapHeight * (width / cue.size)
        if (height > 1f) return cue
        val left = cue.position - anchorFraction(cue.positionAnchor) * cue.size
        val top = cue.line - anchorFraction(cue.lineAnchor) * cue.bitmapHeight
        val middle = left + cue.size / 2
        val newLeft = (middle - width / 2).coerceIn(0f, 1f - width)
        val low = top + cue.bitmapHeight / 2 >= 0.5f
        val newTop = (if (low) top + cue.bitmapHeight - height else top).coerceIn(0f, 1f - height)
        return cue.buildUpon()
            .setSize(width)
            .setBitmapHeight(height)
            .setPosition(newLeft)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(newTop, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .build()
    }

    /** How far into the picture its anchor is: its start edge 0, its middle half, its end edge 1. */
    private fun anchorFraction(anchor: Int): Float = when (anchor) {
        Cue.ANCHOR_TYPE_MIDDLE -> 0.5f
        Cue.ANCHOR_TYPE_END -> 1f
        else -> 0f
    }

    /** Where a grown picture's anchor moves: its start edge out, its middle stays, its end edge out. */
    private fun shift(anchor: Int): Float = when (anchor) {
        Cue.ANCHOR_TYPE_MIDDLE -> 0f
        Cue.ANCHOR_TYPE_END -> 1f
        else -> -1f
    }
}

/**
 * RCTV: the image subtitles' cues with their boxes ([SubtitleImageBox]) before they're drawn; text
 * cues pass as they are (the subtitle view boxes those). The same picture is boxed once (a cue is
 * sent again when the screen redraws).
 */
internal class ImageCueBoxingTextOutput(
    private val delegate: TextOutput,
    /** The box's color: the subtitle style's line background (transparent: no box). */
    private val boxColorProvider: () -> Int,
    /** RCTV: the subtitle size the viewer chose, in percent (100: the picture's own size). */
    private val sizePercentProvider: () -> Int = { 100 }
) : TextOutput {

    /** The last pictures boxed (a Bitmap is its own key: it doesn't compare contents), with the color used. */
    private val recent = object : LinkedHashMap<Bitmap, Pair<Int, Cue>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Bitmap, Pair<Int, Cue>>?): Boolean = size > 6
    }

    override fun onCues(cueGroup: CueGroup) {
        delegate.onCues(CueGroup(boxAll(cueGroup.cues), cueGroup.presentationTimeUs))
    }

    @Suppress("DEPRECATION")
    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        delegate.onCues(boxAll(cues))
    }

    private fun boxAll(cues: List<Cue>): List<Cue> {
        if (cues.none { it.bitmap != null }) return cues
        val color = boxColorProvider()
        val boxed = if ((color ushr 24) == 0) cues else synchronized(recent) {
            cues.map { cue ->
                val bitmap = cue.bitmap ?: return@map cue
                recent[bitmap]?.takeIf { it.first == color }?.second
                    ?: SubtitleImageBox.boxed(cue, color).also { recent[bitmap] = color to it }
            }
        }
        // RCTV: the size the viewer chose, like the text subtitles (only where they go on the screen
        // changes: the picture isn't redrawn).
        val scale = (runCatching { sizePercentProvider() }.getOrDefault(100) / 100f).coerceIn(0.5f, 2f)
        return if (scale == 1f) boxed else boxed.map { SubtitleImageBox.scaled(it, scale) }
    }
}
