package com.nuvio.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val HUB_HERO_AUTO_ADVANCE_MS = 8_000L

/** Side margin of the hub hero: narrower than the rows' 48dp so the banner reads bigger. */
val HubHeroHorizontalPadding: Dp = 24.dp

/** Height of the platform logo above the hub hero. */
val HubLogoHeight: Dp = 44.dp

/**
 * The platform's logo centered at the top of a streaming hub (Disney+, Paramount+, Netflix...),
 * above the hero banner.
 */
@Composable
fun HubLogoHeader(
    @DrawableRes logoRes: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = logoRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(HubLogoHeight)
                .widthIn(max = 260.dp)
        )
    }
}

/**
 * The big hero banner of the streaming hubs: rounded, inset backdrop with the title logo (or name),
 * a short description and page dots underneath. Left/Right switch slides while focused, OK opens the
 * title, and it advances on its own every few seconds when not focused. Takes about two thirds of the
 * screen height.
 */
@Composable
fun HubHeroBanner(
    items: List<MetaPreview>,
    focusRequester: FocusRequester,
    onItemClick: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {}
) {
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    var activeIndex by remember(items) { mutableIntStateOf(0) }
    var isFocused by remember { mutableStateOf(false) }
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val bannerHeight = (screenHeight * 0.68f).dp.coerceIn(260.dp, 640.dp)
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)

    LaunchedEffect(isFocused, items.size) {
        if (items.size <= 1 || isFocused) return@LaunchedEffect
        while (true) {
            delay(HUB_HERO_AUTO_ADVANCE_MS)
            activeIndex = (activeIndex + 1) % items.size
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HubHeroHorizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bannerHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    val focused = it.isFocused || it.hasFocus
                    if (focused != isFocused) {
                        isFocused = focused
                        onFocusChange(focused)
                    }
                }
                .focusable()
                .onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft ->
                            if (activeIndex > 0) { activeIndex--; true } else false
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight ->
                            if (activeIndex < items.size - 1) { activeIndex++; true } else false
                        event.type == KeyEventType.KeyUp && (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                            items.getOrNull(activeIndex)?.let(currentOnItemClick)
                            true
                        }
                        else -> false
                    }
                }
                .clip(shape)
                .then(
                    if (isFocused) Modifier.border(TileFocusStroke, shape)
                    else Modifier.border(BorderStroke(1.dp, NuvioTheme.colors.Border), shape)
                )
                .background(NuvioTheme.colors.BackgroundCard)
        ) {
            Crossfade(targetState = activeIndex, animationSpec = tween(400), label = "hubHero") { index ->
                val item = items.getOrNull(index) ?: return@Crossfade
                HubHeroSlide(item = item)
            }
        }

        if (items.size > 1) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                repeat(items.size) { index ->
                    val active = index == activeIndex
                    Box(
                        modifier = Modifier
                            .size(if (active) 8.dp else 6.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(if (active) Color.White else Color.White.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

@Composable
private fun HubHeroSlide(item: MetaPreview) {
    var logoFailed by remember(item.logo) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = item.backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )
        // Darken the left side and bottom so the logo and text stay readable on any artwork.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.82f),
                        0.45f to Color.Black.copy(alpha = 0.45f),
                        0.75f to Color.Transparent
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.6f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.42f)
                .padding(start = NuvioTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            if (!item.logo.isNullOrBlank() && !logoFailed) {
                AsyncImage(
                    model = item.logo,
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onError = { logoFailed = true },
                    modifier = Modifier.height(104.dp).widthIn(max = 320.dp).fillMaxWidth()
                )
            } else {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val meta = listOfNotNull(
                item.releaseInfo,
                item.imdbRating?.let { String.format(java.util.Locale.US, "★ %.1f", it) }
            ).joinToString("  •  ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }
            item.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
