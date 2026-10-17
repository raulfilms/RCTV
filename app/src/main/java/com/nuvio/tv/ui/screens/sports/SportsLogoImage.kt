package com.nuvio.tv.ui.screens.sports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter as AndroidColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What's behind most logos in Sports: the cards and the page, near black. */
internal val SportsLogoDarkBackground = Color(0xFF1C1C1E)

/** ESPN dark logos that don't exist: those show their usual logo. */
private val MissingDarkLogos = mutableSetOf<String>()

/**
 * A team's or a league's logo that always shows on what's behind it ([background]):
 * - on a dark tile, ESPN's own version of the logo for dark backgrounds (the Yankees' and the
 *   Premier League's in white), when it has one;
 * - and any logo still too dark to see there (navy, black, dark purple...) turned white.
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
    val darkTile = lumaOf(background) < LIGHT_TILE_LUMA
    val darkUrl = remember(url, darkTile) { if (darkTile) espnDarkLogoUrl(url) else null }
    var useDark by remember(url, darkTile) { mutableStateOf(darkUrl != null && darkUrl !in MissingDarkLogos) }
    val shownUrl = if (useDark && darkUrl != null) darkUrl else url
    val request = remember(context, shownUrl, background) {
        ImageRequest.Builder(context)
            .data(shownUrl)
            .transformations(LogoContrastTransformation(background))
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        onError = {
            // No dark version of this one: the usual logo (turned white if it's too dark).
            if (darkUrl != null && shownUrl == darkUrl) {
                MissingDarkLogos.add(darkUrl)
                useDark = false
            }
        },
        modifier = modifier
    )
}

/**
 * ESPN's logo for dark backgrounds, from its usual one: `/i/teamlogos/mlb/500/nyy.png` →
 * `/i/teamlogos/mlb/500-dark/nyy.png`; same for league logos. Null for any other logo.
 */
internal fun espnDarkLogoUrl(url: String): String? {
    if (!url.contains("espncdn.com")) return null
    if (!url.contains("/teamlogos/") && !url.contains("/leaguelogos/")) return null
    if (url.contains("/500-dark/")) return url
    return url.replaceFirst("/500/", "/500-dark/").takeIf { it != url }
}

/** A tile this light keeps the usual logos (the dark versions are made for dark backgrounds). */
private const val LIGHT_TILE_LUMA = 0.6f

/** How light a color looks, 0 (black) to 1 (white). */
private fun lumaOf(color: Color): Float = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

/**
 * Turns a logo white when most of it can't be seen on [background]: when it's dark (navy, black,
 * dark purple) on a dark tile, or nearly the tile's own color. A logo of one dark color becomes all
 * white; one with light parts too keeps them as cut-outs (dark becomes white, light becomes dark).
 */
private class LogoContrastTransformation(background: Color) : Transformation() {
    // Close colors share one cached result.
    private val red = quantize(background.red)
    private val green = quantize(background.green)
    private val blue = quantize(background.blue)

    override val cacheKey: String = "sports_logo_contrast_v1_${red}_${green}_$blue"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val backgroundRgb = Triple(red * 255 / QUANT_STEPS, green * 255 / QUANT_STEPS, blue * 255 / QUANT_STEPS)
        val backgroundLuma = (0.2126f * backgroundRgb.first + 0.7152f * backgroundRgb.second + 0.0722f * backgroundRgb.third) / 255f
        if (backgroundLuma >= LIGHT_TILE_LUMA) return input
        val look = runCatching { lookOf(input, backgroundRgb) }.getOrNull() ?: return input
        if (look.hiddenShare < HIDDEN_SHARE_TO_WHITEN) return input
        val filter: AndroidColorFilter = if (look.lightShare < 0.12f) {
            PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
        } else {
            ColorMatrixColorFilter(DarkToWhite)
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
        fun quantize(channel: Float): Int = (channel.coerceIn(0f, 1f) * QUANT_STEPS).roundToInt()
    }
}

/** Half of a logo or more can't be seen: it's turned white. */
private const val HIDDEN_SHARE_TO_WHITEN = 0.5f

/** Darker than this, a part of a logo is lost on a dark tile. */
private const val DARK_PIXEL_LUMA = 0.2f

/** Closer than this to the tile's color (0 same, 1 black against white), a part of a logo is lost. */
private const val CLOSE_TO_TILE = 0.2f

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

private class LogoLook(val hiddenShare: Float, val lightShare: Float)

/** How much of a logo is lost on the tile, and how much of it is light, from about 2,000 of its pixels. */
private fun lookOf(bitmap: Bitmap, background: Triple<Int, Int, Int>): LogoLook? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val step = maxOf(1, minOf(width, height) / 48)
    val row = IntArray(width)
    var opaque = 0
    var hidden = 0
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
                // Dark: navy, black, dark purple; not a bright red or blue (dim by luma, still seen).
                val dark = luma < DARK_PIXEL_LUMA && maxOf(r, g, b) < 115
                if (dark || distance < CLOSE_TO_TILE) hidden++
                if (luma > 0.7f) light++
            }
            x += step
        }
        y += step
    }
    if (opaque < 12) return null
    return LogoLook(hiddenShare = hidden.toFloat() / opaque, lightShare = light.toFloat() / opaque)
}
