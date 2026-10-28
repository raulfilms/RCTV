package com.nuvio.tv.ui.screens.sports

import android.graphics.Bitmap
import android.os.Build
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What's behind most logos in Sports: the cards and the page, near black. */
internal val SportsLogoDarkBackground = Color(0xFF1C1C1E)

/**
 * A team's or a league's logo (or a country's flag) on what's behind it ([background]), always in
 * its own colors. One that can hardly be seen there (the Yankees' navy on navy, a red flag on red)
 * gets a thin white outline (dark on a light tile).
 */
@Composable
internal fun SportsLogoImage(
    url: String?,
    background: Color,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    if (url.isNullOrBlank()) return
    val context = LocalContext.current
    val request = remember(context, url, background) {
        ImageRequest.Builder(context)
            .data(url)
            .transformations(LogoContrastTransformation(background))
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

/** A tile this light gets a dark outline around a logo that can't be seen on it (white on white). */
private const val LIGHT_TILE_LUMA = 0.6f

/** Less than this much of a logo seen on its tile: it gets an outline. */
private const val VISIBLE_SHARE_TO_KEEP = 0.25f

/** A part of a logo is seen on its tile when it's this much lighter or darker... */
private const val VISIBLE_LUMA_DIFFERENCE = 0.22f

/** ...or this different a color (0 the same, 1 black against white). */
private const val VISIBLE_COLOR_DISTANCE = 0.35f

/** How thick the outline is, as a share of the logo's longer side. */
private const val OUTLINE_SHARE = 0.025f

/**
 * A logo always keeps its own colors (RCTV: none is turned white or dark any more: Calgary's red
 * horse, Atlanta United's and Inter Miami's crests, Albania's flag). When almost none of it can be
 * seen on [background] (the Yankees' navy on navy, a red flag on red), it gets a thin outline around
 * it instead, white on a dark tile and dark on a light one, so it stands out in its own colors.
 */
private class LogoContrastTransformation(background: Color) : Transformation() {
    // Close colors share one cached result.
    private val red = quantize(background.red)
    private val green = quantize(background.green)
    private val blue = quantize(background.blue)

    override val cacheKey: String = "sports_logo_outline_v4_${red}_${green}_$blue"

    override suspend fun transform(source: Bitmap, size: Size): Bitmap {
        // RCTV: no bigger than the space it's shown in (a 500-px logo decoded for a 180-px tile,
        // hundreds of them on Sports' pages, took more memory than a 2 GB TV has).
        val input = runCatching { fittedTo(source, size) }.getOrDefault(source)
        // RCTV (MEM-01): a bitmap replaced here is freed at once. Coil keeps neither the decoded
        // logo nor what a transformation returns in between (only the final result), so nothing else
        // uses it; left to the garbage collector, hundreds of them piled up in the native memory of
        // a Mi Box 4 (over 1 GB on Sports, half of it freed only by the next collection) and the
        // system closed the app.
        if (input !== source) source.recycle()
        val backgroundRgb = Triple(red * 255 / QUANT_STEPS, green * 255 / QUANT_STEPS, blue * 255 / QUANT_STEPS)
        val backgroundLuma = (0.2126f * backgroundRgb.first + 0.7152f * backgroundRgb.second + 0.0722f * backgroundRgb.third) / 255f
        val visibleShare = runCatching { visibleShareOf(input, backgroundRgb, backgroundLuma) }.getOrNull()
            ?: return forDisplay(input)
        // Enough of it shows: as it is.
        if (visibleShare >= VISIBLE_SHARE_TO_KEEP) return forDisplay(input)
        val outlineColor = if (backgroundLuma >= LIGHT_TILE_LUMA) LOGO_DARK else android.graphics.Color.WHITE
        val withOutline = runCatching { outlined(input, outlineColor) }.getOrNull() ?: return forDisplay(input)
        input.recycle()
        return forDisplay(withOutline)
    }

    override fun equals(other: Any?): Boolean = other is LogoContrastTransformation && other.cacheKey == cacheKey
    override fun hashCode(): Int = cacheKey.hashCode()

    private companion object {
        const val QUANT_STEPS = 16
        val LOGO_DARK: Int = 0xFF1C1C1E.toInt()
        fun quantize(channel: Float): Int = (channel.coerceIn(0f, 1f) * QUANT_STEPS).roundToInt()
    }
}

/**
 * RCTV (MEM-01): the finished logo as a hardware bitmap (Android 9 and later): its pixels live in
 * graphics memory, not in the app's own native memory, which a 32-bit app on a 2 GB TV runs out of
 * first. Other images already are (Coil does it for any image without a transformation; a
 * transformation needs the pixels, so logos were the ones left in native memory). If the copy
 * can't be made, the logo stays as it is.
 */
private fun forDisplay(bitmap: Bitmap): Bitmap {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || bitmap.config == Bitmap.Config.HARDWARE) return bitmap
    val hardware = runCatching { bitmap.copy(Bitmap.Config.HARDWARE, false) }.getOrNull() ?: return bitmap
    bitmap.recycle()
    return hardware
}

/** The biggest a logo is kept when its space isn't known. */
private const val LOGO_MAX_PX = 512

/** [input] shrunk to fit [size] (the space it's shown in) when it's clearly bigger; else as it is. */
private fun fittedTo(input: Bitmap, size: Size): Bitmap {
    val maxWidth = (size.width as? Dimension.Pixels)?.px?.takeIf { it > 0 } ?: LOGO_MAX_PX
    val maxHeight = (size.height as? Dimension.Pixels)?.px?.takeIf { it > 0 } ?: LOGO_MAX_PX
    val scale = minOf(maxWidth.toFloat() / input.width, maxHeight.toFloat() / input.height)
    if (scale >= 0.8f) return input
    val width = (input.width * scale).roundToInt().coerceAtLeast(1)
    val height = (input.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(input, width, height, true)
}

/**
 * [input] in its own colors with an outline of [color] around its shape: its silhouette in [color],
 * drawn a little to every side of it, and the logo on top. A little room is added around it so the
 * outline isn't cut off at the edges (a flag fills its whole image).
 */
private fun outlined(input: Bitmap, color: Int): Bitmap {
    val radius = maxOf(2f, maxOf(input.width, input.height) * OUTLINE_SHARE)
    val pad = ceil(radius).toInt() + 1
    val output = Bitmap.createBitmap(input.width + 2 * pad, input.height + 2 * pad, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val silhouette = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }
    // Two rings of copies (all the way out and half way) so no gap is left at sharp corners.
    for ((share, steps) in OUTLINE_RINGS) {
        val distance = radius * share
        for (i in 0 until steps) {
            val angle = 2.0 * PI * i / steps
            canvas.drawBitmap(
                input,
                pad + (distance * cos(angle)).toFloat(),
                pad + (distance * sin(angle)).toFloat(),
                silhouette
            )
        }
    }
    canvas.drawBitmap(input, pad.toFloat(), pad.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    return output
}

private val OUTLINE_RINGS = listOf(1f to 24, 0.5f to 12)

/** How much of a logo can be seen on the tile (0 to 1), from about 2,000 of its pixels. */
private fun visibleShareOf(bitmap: Bitmap, background: Triple<Int, Int, Int>, backgroundLuma: Float): Float? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val step = maxOf(1, minOf(width, height) / 48)
    val row = IntArray(width)
    var opaque = 0
    var visible = 0
    var y = 0
    while (y < height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        var x = 0
        while (x < width) {
            val pixel = row[x]
            if ((pixel ushr 24) >= 110) {
                opaque++
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                val dr = (r - background.first).toFloat()
                val dg = (g - background.second).toFloat()
                val db = (b - background.third).toFloat()
                val distance = sqrt(dr * dr + dg * dg + db * db) / 441.7f
                if (abs(luma - backgroundLuma) >= VISIBLE_LUMA_DIFFERENCE || distance >= VISIBLE_COLOR_DISTANCE) visible++
            }
            x += step
        }
        y += step
    }
    if (opaque < 12) return null
    return visible.toFloat() / opaque
}
