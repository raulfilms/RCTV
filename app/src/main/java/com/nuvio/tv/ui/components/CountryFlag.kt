package com.nuvio.tv.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.util.Locale

/** Height of a title card's flag: easy to recognize from the couch, small beside the artwork. */
val CountryFlagHeight: Dp = 20.dp

private val FlagShape = RoundedCornerShape(3.dp)

/**
 * RCTV: a country's flag ([countryCode], ISO 3166-1 alpha-2) at a set height, in its own
 * proportions (never stretched or cut), with a thin edge and a soft shadow so a white or pale flag
 * still stands out on bright artwork. The images come from flagcdn.com (Flagpedia) and are kept in
 * the app's image cache like any card's picture. Nothing shows until it's loaded, or if it can't be.
 */
@Composable
fun CountryFlag(
    countryCode: String,
    modifier: Modifier = Modifier,
    height: Dp = CountryFlagHeight
) {
    val code = countryCode.trim().lowercase(Locale.ROOT)
    if (code.length != 2 || !code.all { it in 'a'..'z' }) return
    val context = LocalContext.current
    var loaded by remember(code) { mutableStateOf(false) }
    var failed by remember(code) { mutableStateOf(false) }
    if (failed) return
    val request = remember(context, code) {
        ImageRequest.Builder(context)
            // 80 px tall: sharp at this size on 1080p and 4K screens.
            .data("https://flagcdn.com/h80/$code.png")
            .memoryCacheKey("flag_h80_$code")
            .diskCacheKey("flag_h80_$code")
            .build()
    }
    val countryName = remember(code) { Locale("", code.uppercase(Locale.ROOT)).displayCountry }
    AsyncImage(
        model = request,
        contentDescription = countryName,
        contentScale = ContentScale.Fit,
        onSuccess = { loaded = true },
        onError = { failed = true },
        modifier = modifier
            .height(height)
            .widthIn(max = height * 2.4f)
            .then(
                if (loaded) {
                    Modifier
                        .shadow(elevation = 3.dp, shape = FlagShape, clip = false)
                        .clip(FlagShape)
                        .border(width = 0.5.dp, color = Color.Black.copy(alpha = 0.35f), shape = FlagShape)
                } else {
                    Modifier
                }
            )
    )
}
