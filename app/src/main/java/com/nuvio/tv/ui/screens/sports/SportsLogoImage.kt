package com.nuvio.tv.ui.screens.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter as AndroidColorFilter
import android.graphics.ColorMatrixColorFilter
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
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What's behind most logos in Sports: the cards and the page, near black. */
internal val SportsLogoDarkBackground = Color(0xFF1C1C1E)

/**
 * A team's or a league's logo on what's behind it ([background]), in its own colors. Only a logo
 * that can't be seen there (the Yankees' navy on navy, Juventus' black on black, the Premier
 * League's purple on purple) is turned white (dark on a light tile).
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

/** A tile this light gets a dark logo when its own can't be seen (a white logo on white). */
private const val LIGHT_TILE_LUMA = 0.6f

/** Less than this much of a logo seen on its tile: it's turned white (or dark). */
private const val VISIBLE_SHARE_TO_KEEP = 0.25f

/** A part of a logo is seen on its tile when it's this much lighter or darker... */
private const val VISIBLE_LUMA_DIFFERENCE = 0.22f

/** ...or this different a color (0 the same, 1 black against white). */
private const val VISIBLE_COLOR_DISTANCE = 0.35f

/**
 * Keeps a logo's own colors whenever a fair part of it can be seen on [background] (the Celtics'
 * white ring on green, the Bulls' black outline on red). When almost none of it can (one color,
 * the tile's own or as dark as it), it's turned white on a dark tile, dark on a light one: a
 * one-color logo all white; one with light parts too keeps them as cut-outs.
 */
private class LogoContrastTransformation(background: Color) : Transformation() {
    // Close colors share one cached result.
    private val red = quantize(background.red)
    private val green = quantize(background.green)
    private val blue = quantize(background.blue)

    override val cacheKey: String = "sports_logo_contrast_v2_${red}_${green}_$blue"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val backgroundRgb = Triple(red * 255 / QUANT_STEPS, green * 255 / QUANT_STEPS, blue * 255 / QUANT_STEPS)
        val backgroundLuma = (0.2126f * backgroundRgb.first + 0.7152f * backgroundRgb.second + 0.0722f * backgroundRgb.third) / 255f
        val look = runCatching { lookOf(input, backgroundRgb, backgroundLuma) }.getOrNull() ?: return input
        // Enough of it shows: its own colors.
        if (look.visibleShare >= VISIBLE_SHARE_TO_KEEP) return input
        val lightTile = backgroundLuma >= LIGHT_TILE_LUMA
        val filter: AndroidColorFilter = when {
            lightTile -> PorterDuffColorFilter(LOGO_DARK, PorterDuff.Mode.SRC_IN)
            look.lightShare < 0.12f -> PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
            else -> ColorMatrixColorFilter(DarkToWhite)
        }
        return runCatching {
            val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(input, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter })
            output
        }.getOrDefault(input)
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
 * Dark becomes white and light becomes dark (by how light each color looks): R, G, B =
 * 342 − 1.71 × luma, so anything darker than 20% is white and anything lighter than 78% is black.
 */
private val DarkToWhite: FloatArray = run {
    val k = 1.711f
    val r = -0.2126f * k
    val g = -0.7152f * k
    val b = -0.0722f * k
    val offset = 342.3f
    floatArrayOf(
        r, g, b, 0f, offset,
        r, g, b, 0f, offset,
        r, g, b, 0f, offset,
        0f, 0f, 0f, 1f, 0f
    )
}

private class LogoLook(val visibleShare: Float, val lightShare: Float)

/** How much of a logo can be seen on the tile, and how much of it is light, from about 2,000 of its pixels. */
private fun lookOf(bitmap: Bitmap, background: Triple<Int, Int, Int>, backgroundLuma: Float): LogoLook? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val step = maxOf(1, minOf(width, height) / 48)
    val row = IntArray(width)
    var opaque = 0
    var visible = 0
    var light = 0
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
                if (luma > 0.7f) light++
            }
            x += step
        }
        y += step
    }
    if (opaque < 12) return null
    return LogoLook(visibleShare = visible.toFloat() / opaque, lightShare = light.toFloat() / opaque)
}
