package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.localizeEpisodeTitle

/**
 * The pause screen. RCTV, as Disney+: at the bottom left, the streaming service the title is on
 * (else its network or studio), the title's logo, for a show the episode's name, then the year, the
 * age rating and the length, then a short description.
 */
@Composable
fun PauseOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    title: String,
    logo: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    modifier: Modifier = Modifier,
    @DrawableRes brandLogoRes: Int? = null,
    brandLogoUrl: String? = null,
    brandName: String? = null,
    ageRating: String? = null,
    runtime: String? = null,
    showClock: Boolean = true
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onClose,
        modifier = modifier,
        captureKeys = false,
        dismissOnBackgroundClick = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = NuvioTheme.spacing.huge,
            end = NuvioTheme.spacing.huge,
            top = 40.dp,
            bottom = 120.dp
        ),
        topEndContent = {
            if (showClock) {
                PauseOverlayClock()
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            PauseMetadataView(
                title = title,
                logo = logo,
                episodeTitle = episodeTitle,
                season = season,
                episode = episode,
                year = year,
                type = type,
                description = description,
                brandLogoRes = brandLogoRes,
                brandLogoUrl = brandLogoUrl,
                brandName = brandName,
                ageRating = ageRating,
                runtime = runtime
            )
        }
    }
}

@Composable
private fun PauseOverlayClock(modifier: Modifier = Modifier) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMillis = current
            val delayMs = (60_000L - (current % 60_000L)).coerceAtLeast(1_000L)
            delay(delayMs)
        }
    }

    Text(
        text = formatter.format(Date(nowMillis)),
        style = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 34.sp
        ),
        color = Color.White.copy(alpha = 0.95f),
        modifier = modifier
    )
}

@Composable
private fun PauseMetadataView(
    title: String,
    logo: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    @DrawableRes brandLogoRes: Int?,
    brandLogoUrl: String?,
    brandName: String?,
    ageRating: String?,
    runtime: String?
) {
    val context = LocalContext.current
    val isEpisode = type in listOf("series", "tv") && season != null && episode != null
    val episodeLabel = if (isEpisode) stringResource(R.string.season_episode_format, season!!, episode!!) else null
    // "2009 • PG-13 • 2h 42m"; a show's episode: "S1 E3 • 2023 • TV-MA • 52m".
    val info = listOfNotNull(
        episodeLabel,
        year?.takeIf { it.isNotBlank() },
        ageRating?.takeIf { it.isNotBlank() },
        formatHeroRuntime(runtime)
    ).joinToString("  •  ")

    Column(
        modifier = Modifier.widthIn(max = 620.dp)
    ) {
        // The streaming service (the app's own logo), else the network or studio (in white).
        when {
            brandLogoRes != null -> Image(
                painter = painterResource(brandLogoRes),
                contentDescription = brandName,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier
                    .padding(bottom = NuvioTheme.spacing.md)
                    .height(34.dp)
                    .widthIn(max = 160.dp)
            )
            !brandLogoUrl.isNullOrBlank() -> {
                var brandFailed by remember(brandLogoUrl) { mutableStateOf(false) }
                if (!brandFailed) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(brandLogoUrl)
                            .memoryCacheKey(brandLogoUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = brandName,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier
                            .padding(bottom = NuvioTheme.spacing.md)
                            .height(30.dp)
                            .widthIn(max = 160.dp),
                        onError = { brandFailed = true }
                    )
                }
            }
        }

        // The title's logo (its name when it has none).
        var logoFailed by remember(logo) { mutableStateOf(false) }
        if (!logo.isNullOrBlank() && !logoFailed) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(logo)
                    .memoryCacheKey(logo)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier
                    .height(88.dp)
                    .widthIn(max = 420.dp),
                onError = { logoFailed = true }
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // A show: the episode's name, right under its logo.
        if (isEpisode && !episodeTitle.isNullOrBlank()) {
            Text(
                text = episodeTitle.localizeEpisodeTitle(context),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = NuvioTheme.spacing.md)
            )
        }

        if (info.isNotBlank()) {
            Text(
                text = info,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = NuvioTheme.spacing.md)
            )
        }

        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDirection = description.contentTextDirection()
                ),
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .padding(top = NuvioTheme.spacing.sm)
            )
        }
    }
}
