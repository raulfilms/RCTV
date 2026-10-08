package com.nuvio.tv.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedGenreLabel
import kotlinx.coroutines.delay

/*
 * Pieces of the Apple TV (tvOS 26) style home screen:
 *  - AppleHeroCarousel: full-bleed featured carousel with logo, meta line,
 *    two-line description, glass Play / + / i buttons, a "next" chevron and
 *    glass page dots.
 *  - AppleAmbientBackdrop: the soft blurred wash of the featured artwork that
 *    sits behind the rows once the hero scrolls away.
 *  - AppleRowTitle and the shared measurements used by the rows.
 */

/** Left edge shared by the hero text and every row, so everything lines up like on Apple TV. */
val AppleTvContentStart: Dp = 40.dp

/** Space between cards in a row. */
val AppleTvCardSpacing: Dp = 18.dp

/** How much a focused card or button grows. */
const val AppleTvFocusScale: Float = 1.08f

/** Soft drop shadow under a focused card. */
@OptIn(ExperimentalTvMaterial3Api::class)
val AppleTvFocusGlow: Glow = Glow(elevationColor = Color.Black.copy(alpha = 0.55f), elevation = 18.dp)

val AppleTvTextShadow: Shadow = Shadow(
    color = Color.Black.copy(alpha = 0.45f),
    offset = Offset(0f, 1.5f),
    blurRadius = 6f
)

private const val HERO_AUTO_ADVANCE_MS = 9_000L
private val HeroPillShape = RoundedCornerShape(percent = 50)
private val GlassFill = Color.White.copy(alpha = 0.16f)
private val GlassEdge = Color.White.copy(alpha = 0.38f)
private val OnWhite = Color(0xFF1C1C1E)

/** Row header ("Continue Watching", "Top 10 …"). */
@Composable
fun AppleRowTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(shadow = AppleTvTextShadow),
        modifier = modifier.padding(start = AppleTvContentStart, end = AppleTvContentStart, bottom = 10.dp)
    )
}

/**
 * Full-screen, heavily blurred copy of the featured artwork. It is decoded at a
 * tiny size and stretched, which blurs it on every Android version; on
 * Android 12+ a real blur is added on top to smooth it further.
 */
@Composable
fun AppleAmbientBackdrop(
    imageUrl: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(modifier = modifier.background(Color(0xFF1C1C1E))) {
        Crossfade(
            targetState = imageUrl,
            animationSpec = tween(durationMillis = 700),
            label = "appleAmbientBackdrop"
        ) { url ->
            if (!url.isNullOrBlank()) {
                val model = remember(context, url) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(width = 64, height = 36)
                        .memoryCacheKey("${url}_apple_ambient")
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(48.dp)
                            else Modifier
                        )
                )
            }
        }
        // Tint so white text stays readable on bright artwork.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF26262A).copy(alpha = 0.58f))
        )
    }
}

/**
 * Apple TV style featured carousel.
 *
 * @param heroHeight height of the hero slot in the list (the rows start below it).
 * @param imageHeight height of the artwork, normally the full screen, so it keeps
 *   showing behind the first row until the user scrolls.
 * @param scrollFraction 0 when the page is at the top, 1 once the hero has scrolled
 *   away; read only while drawing, so scrolling never recomposes the hero.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppleHeroCarousel(
    items: StableList<MetaPreview>,
    heroHeight: Dp,
    imageHeight: Dp,
    scrollFraction: () -> Float,
    onPlay: (MetaPreview) -> Unit,
    onDetails: (MetaPreview) -> Unit,
    onToggleLibrary: (MetaPreview) -> Unit,
    isInLibrary: (MetaPreview) -> Boolean,
    modifier: Modifier = Modifier,
    playFocusRequester: FocusRequester? = null,
    initialActiveIndex: Int = 0,
    onItemFocus: (MetaPreview) -> Unit = {},
    onActiveItemChanged: (MetaPreview) -> Unit = {}
) {
    if (items.isEmpty()) return

    val currentOnItemFocus by rememberUpdatedState(onItemFocus)
    val currentOnActiveItemChanged by rememberUpdatedState(onActiveItemChanged)
    val currentScrollFraction by rememberUpdatedState(scrollFraction)

    var activeIndex by remember {
        mutableIntStateOf(initialActiveIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    }
    val safeIndex = activeIndex.coerceIn(0, items.size - 1)
    val activeItem = items[safeIndex]
    var isFocused by remember { mutableStateOf(false) }
    val internalPlayRequester = remember { FocusRequester() }
    val playRequester = playFocusRequester ?: internalPlayRequester

    fun showNext() {
        if (items.size > 1) activeIndex = (safeIndex + 1) % items.size
    }

    LaunchedEffect(safeIndex, isFocused) {
        if (isFocused) items.getOrNull(safeIndex)?.let { currentOnItemFocus(it) }
    }
    LaunchedEffect(safeIndex, items) {
        items.getOrNull(safeIndex)?.let { currentOnActiveItemChanged(it) }
    }
    // Rotate on its own while the viewer is not on the buttons and the hero is on screen.
    LaunchedEffect(isFocused, items.size) {
        if (items.size <= 1 || isFocused) return@LaunchedEffect
        while (true) {
            delay(HERO_AUTO_ADVANCE_MS)
            if (currentScrollFraction() < 0.5f) {
                activeIndex = (activeIndex + 1) % items.size
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            .onFocusChanged { isFocused = it.hasFocus }
    ) {
        AppleHeroArtwork(
            items = items,
            activeIndex = safeIndex,
            heroHeight = heroHeight,
            imageHeight = imageHeight,
            scrollFraction = scrollFraction
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = AppleTvContentStart, end = AppleTvContentStart, bottom = 34.dp)
        ) {
            Crossfade(
                targetState = safeIndex,
                animationSpec = tween(durationMillis = 350),
                label = "appleHeroInfo"
            ) { index ->
                items.getOrNull(index)?.let { AppleHeroInfo(item = it) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // The buttons stay put while the slides change, so focus never jumps.
            Row(
                modifier = Modifier
                    .focusRestorer { playRequester }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppleGlassButton(
                    onClick = { onPlay(activeItem) },
                    shape = HeroPillShape,
                    modifier = Modifier
                        .focusRequester(playRequester)
                        .height(38.dp)
                        .widthIn(min = 116.dp)
                ) { color ->
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.hero_play),
                            color = color,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
                AppleGlassIconButton(
                    icon = if (isInLibrary(activeItem)) Icons.Filled.Check else Icons.Filled.Add,
                    onClick = { onToggleLibrary(activeItem) }
                )
                AppleGlassIconButton(
                    icon = Icons.Outlined.Info,
                    onClick = { onDetails(activeItem) }
                )
                if (items.size > 1) {
                    AppleGlassIconButton(
                        icon = Icons.Filled.ChevronRight,
                        onClick = { showNext() },
                        bare = true,
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                showNext()
                                true
                            } else {
                                false
                            }
                        }
                    )
                }
            }
        }

        if (items.size > 1) {
            AppleHeroPageDots(
                count = items.size,
                activeIndex = safeIndex,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun AppleHeroArtwork(
    items: StableList<MetaPreview>,
    activeIndex: Int,
    heroHeight: Dp,
    imageHeight: Dp,
    scrollFraction: () -> Float
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val requestWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }.coerceAtLeast(1)
    }
    val requestHeightPx = remember(imageHeight, density) {
        with(density) { imageHeight.roundToPx() }.coerceAtLeast(1)
    }
    val heroFraction = (heroHeight / imageHeight).coerceIn(0.1f, 1f)

    // Taller than its slot: the artwork keeps running behind the first row.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .height(imageHeight)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Soft shade at the left and bottom so the text and buttons read well.
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.35f to Color.Black.copy(alpha = 0.28f),
                            0.6f to Color.Transparent
                        )
                    )
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.45f)
                        )
                    )
                )
                // Fade the artwork out: at the top of the page it fades near the
                // bottom of the screen; once scrolled it ends with the hero slot.
                val f = scrollFraction().coerceIn(0f, 1f)
                val fadeEnd = size.height * (1f + (heroFraction - 1f) * f)
                val fadeLength = size.height * (0.30f - 0.18f * f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = fadeEnd - fadeLength,
                        endY = fadeEnd
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        Crossfade(
            targetState = activeIndex,
            animationSpec = tween(durationMillis = 600),
            modifier = Modifier.fillMaxSize(),
            label = "appleHeroArtwork"
        ) { index ->
            val item = items.getOrNull(index) ?: return@Crossfade
            val url = item.backdropUrl
            val model = remember(context, url, requestWidthPx, requestHeightPx) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(width = requestWidthPx, height = requestHeightPx)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun AppleHeroInfo(item: MetaPreview) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val logoRequestWidthPx = remember(density) { with(density) { 300.dp.roundToPx() } }
    val logoRequestHeightPx = remember(density) { with(density) { 90.dp.roundToPx() } }
    val logoModel = remember(context, item.logo, logoRequestWidthPx, logoRequestHeightPx) {
        item.logo?.takeIf { it.isNotBlank() }?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(width = logoRequestWidthPx, height = logoRequestHeightPx)
                .crossfade(true)
                .build()
        }
    }
    var logoFailed by remember(item.logo) { mutableStateOf(false) }
    val metaText = remember(context, item.apiType, item.genres) {
        buildList {
            localizedContentType(context, item.apiType).takeIf { it.isNotBlank() }?.let { add(it) }
            item.genres
                .filter { it.isNotBlank() }
                .take(2)
                .forEach { add(localizedGenreLabel(context, it)) }
        }.joinToString(separator = " · ")
    }
    val ageRating = item.ageRating?.trim()?.takeIf { it.isNotBlank() }
    val shadowStyle = TextStyle(shadow = AppleTvTextShadow)

    Column(modifier = Modifier.widthIn(max = 400.dp)) {
        if (logoModel != null && !logoFailed) {
            AsyncImage(
                model = logoModel,
                contentDescription = item.name,
                onError = { logoFailed = true },
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier
                    .height(84.dp)
                    .widthIn(max = 300.dp)
                    .fillMaxWidth()
            )
        } else {
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = shadowStyle
            )
        }

        if (metaText.isNotBlank() || ageRating != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = shadowStyle,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (ageRating != null) {
                    if (metaText.isNotBlank()) Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .border(
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.85f)),
                                shape = RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = ageRating,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        item.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = shadowStyle
            )
        }
    }
}

@Composable
private fun AppleHeroPageDots(
    count: Int,
    activeIndex: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(HeroPillShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.18f)), HeroPillShape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val active = index == activeIndex
            val width by animateDpAsState(
                targetValue = if (active) 16.dp else 5.dp,
                animationSpec = tween(durationMillis = 250),
                label = "appleHeroDotWidth"
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = 5.dp)
                    .clip(HeroPillShape)
                    .background(if (active) Color.White else Color.White.copy(alpha = 0.45f))
            )
        }
    }
}

/**
 * Glass button: translucent with a thin light edge when idle, solid white with
 * dark content when focused (the tvOS focus look).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppleGlassButton(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    bare: Boolean = false,
    content: @Composable (contentColor: Color) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val contentColor by animateColorAsState(
        targetValue = if (focused) OnWhite else Color.White,
        animationSpec = tween(durationMillis = 150),
        label = "appleGlassButtonContent"
    )
    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused || it.hasFocus },
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = if (bare) Color.Transparent else GlassFill,
            focusedContainerColor = Color.White
        ),
        border = CardDefaults.border(
            border = if (bare) Border.None else Border(border = BorderStroke(1.dp, GlassEdge), shape = shape),
            focusedBorder = Border.None
        ),
        scale = CardDefaults.scale(focusedScale = AppleTvFocusScale),
        glow = CardDefaults.glow(focusedGlow = AppleTvFocusGlow)
    ) {
        // Wraps its content horizontally (so the Play pill fits its label) and centers it.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            content(contentColor)
        }
    }
}

@Composable
private fun AppleGlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bare: Boolean = false
) {
    AppleGlassButton(
        onClick = onClick,
        shape = CircleShape,
        bare = bare,
        modifier = modifier.size(38.dp)
    ) { color ->
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(if (bare) 26.dp else 20.dp)
        )
    }
}
