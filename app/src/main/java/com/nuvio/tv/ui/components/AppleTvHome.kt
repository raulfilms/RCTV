@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

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
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.style.TextAlign
import com.nuvio.tv.domain.model.StreamingService
import com.nuvio.tv.domain.model.MetaPreview as AppleMetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextAlign as AppleTextAlign
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource

/*
 * Pieces of the Apple TV (tvOS 26) style home screen:
 *  - AppleHeroCarousel: full-bleed featured carousel with logo, meta line,
 *    two-line description, glass Play / + / i buttons, a "next" chevron and
 *    glass page dots.
 *  - AppleAmbientBackdrop: the soft blurred wash of the featured artwork that
 *    sits behind the rows once the hero scrolls away.
 *  - AppleRowTitle and the shared measurements used by the rows.
 */

/** Left edge shared by the hero text and every row (the sheet's safe-x margin). */
val AppleTvContentStart: Dp = AppleTvSpacing.SafeX

/** Space between cards in a row (the sheet's grid gap). */
val AppleTvCardSpacing: Dp = AppleTvSpacing.GridGap

/** How much a focused card or button grows (the sheet's focus-scale). */
const val AppleTvFocusScale: Float = 1.1f

/** Shadow under a focused card (the sheet's shadow-focus: black 55 %, 24 dp soft). */
@OptIn(ExperimentalTvMaterial3Api::class)
val AppleTvFocusGlow: Glow = Glow(elevationColor = Color.Black.copy(alpha = 0.55f), elevation = 24.dp)

val AppleTvTextShadow: Shadow = Shadow(
    color = Color.Black.copy(alpha = 0.45f),
    offset = Offset(0f, 1.5f),
    blurRadius = 6f
)

private const val HERO_AUTO_ADVANCE_MS = 9_000L
private val HeroPillShape = AppleTvRadius.Pill
private val GlassFill = AppleTvColors.Glass
private val OnWhite = AppleTvColors.FocusLabel

/** Row header ("Continue Watching", "Top 10 …"). */
@Composable
fun AppleRowTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = AppleTvColors.Label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = AppleTvType.Headline.copy(shadow = AppleTvTextShadow),
        modifier = modifier.padding(start = AppleTvContentStart, end = AppleTvContentStart, bottom = 10.dp)
    )
}

/** False while the Apple TV style home is scrolled down: the "‹ Home" pill hides then, like tvOS. */
object AppleHomeChrome {
    val showSectionPill = mutableStateOf(true)

    // Home and Movies & TV show the same page; only the one on screen now drives the pill,
    // so the page that is leaving can't switch it back on while the new one is scrolled down.
    private var owner: Any? = null

    fun report(page: Any, atTop: Boolean) {
        owner = page
        showSectionPill.value = atTop
    }

    fun release(page: Any) {
        if (owner === page) {
            owner = null
            showSectionPill.value = true
        }
    }
}

// Apple TV's plain gray backdrop behind the rows (tvOS 26), slightly lighter at the top.
private val AppleBackdropTop = Color(0xFF727277)
private val AppleBackdropBottom = Color(0xFF5E5E63)

/**
 * The page background behind the rows: Apple TV's plain gray. At the top of the
 * home page the featured artwork covers it; it shows as soon as the rows scroll up.
 * [imageUrl] is kept for callers but no longer tints the background.
 */
@Composable
fun AppleAmbientBackdrop(
    @Suppress("UNUSED_PARAMETER") imageUrl: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(AppleBackdropTop, AppleBackdropBottom))
        )
    )
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
                horizontalArrangement = Arrangement.spacedBy(AppleTvSpacing.Space3),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppleGlassButton(
                    onClick = { onPlay(activeItem) },
                    shape = HeroPillShape,
                    minWidth = 116.dp,
                    modifier = Modifier
                        .focusRequester(playRequester)
                        .height(38.dp)
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
                        Spacer(modifier = Modifier.width(AppleTvSpacing.Space1))
                        Text(
                            text = stringResource(R.string.hero_play),
                            color = color,
                            style = AppleTvType.Callout,
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
                // Short fade: at the top the artwork runs to the bottom of the screen; once
                // scrolled it ends in a clean edge over the gray, as on Apple TV.
                val fadeLength = size.height * 0.04f
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
                color = AppleTvColors.Label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = AppleTvType.Title2.copy(shadow = AppleTvTextShadow)
            )
        }

        if (metaText.isNotBlank() || ageRating != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        color = AppleTvColors.LabelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = AppleTvType.Caption2.copy(shadow = AppleTvTextShadow),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                if (ageRating != null) {
                    if (metaText.isNotBlank()) Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .border(
                                border = BorderStroke(1.dp, AppleTvColors.LabelSecondary),
                                shape = RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = ageRating,
                            color = AppleTvColors.LabelSecondary,
                            style = AppleTvType.Caption2,
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
                color = AppleTvColors.LabelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = AppleTvType.Body.copy(shadow = AppleTvTextShadow)
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
            .background(GlassFill)
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
 * Glass button (capsule or circle): Liquid Glass with white content when idle, solid white
 * with black content when focused, growing with a soft shadow (the tvOS focus look).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppleGlassButton(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    /** Narrowest the button gets; its content is centered inside. */
    minWidth: Dp = 0.dp,
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
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        scale = CardDefaults.scale(focusedScale = AppleTvFocusScale),
        glow = CardDefaults.glow(focusedGlow = AppleTvFocusGlow)
    ) {
        // At least as wide as the button (minWidth), so the icon or label is centered both ways.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = minWidth),
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
        minWidth = 38.dp,
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

/**
 * "Streaming Services" row: one tile per service with its logo on a soft wash of the
 * logo's own colors. Selecting a tile opens that service's page.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppleStreamingServicesRow(
    title: String,
    services: List<StreamingService>,
    onServiceClick: (StreamingService) -> Unit,
    modifier: Modifier = Modifier,
    rowFocusRequester: FocusRequester? = null,
    initialFocusIndex: Int = -1,
    onItemFocused: (Int) -> Unit = {}
) {
    if (services.isEmpty()) return

    val itemRequesters = remember(services.size) { List(services.size) { FocusRequester() } }
    var lastFocusedIndex by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    // Coming back from a service page: put focus back on the tile that was opened.
    LaunchedEffect(initialFocusIndex, services.size) {
        val target = initialFocusIndex
        if (target !in services.indices) return@LaunchedEffect
        runCatching { listState.scrollToItem(target) }
        repeat(4) {
            withFrameNanos { }
            val focused = runCatching { itemRequesters[target].requestFocus(); true }.getOrDefault(false)
            if (focused) return@LaunchedEffect
        }
    }

    // Keep the focused tile at the row's left edge, like the other rows.
    val density = LocalDensity.current
    val parentSpec = LocalBringIntoViewSpec.current
    val startPx = with(density) { AppleTvContentStart.toPx() }
    val horizontalSpec = remember(parentSpec, startPx) {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = parentSpec.scrollAnimationSpec
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val childSize = kotlin.math.abs(size)
                val space = containerSize - startPx
                val leading = if (childSize <= containerSize && space < childSize) containerSize - childSize else startPx
                return offset - leading
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AppleRowTitle(text = title)
        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (rowFocusRequester != null) Modifier.focusRequester(rowFocusRequester) else Modifier)
                    .focusRestorer { itemRequesters.getOrNull(lastFocusedIndex) ?: FocusRequester.Default }
                    .focusGroup(),
                contentPadding = PaddingValues(start = AppleTvContentStart, end = AppleTvContentStart),
                horizontalArrangement = Arrangement.spacedBy(AppleTvCardSpacing)
            ) {
                itemsIndexed(
                    items = services,
                    key = { _, service -> "streaming_service_${service.key}" }
                ) { index, service ->
                    AppleStreamingServiceTile(
                        service = service,
                        onClick = { onServiceClick(service) },
                        modifier = Modifier
                            .focusRequester(itemRequesters[index])
                            .onFocusChanged {
                                if (it.isFocused) {
                                    lastFocusedIndex = index
                                    onItemFocused(index)
                                }
                            }
                    )
                }
            }
        }
    }
}

/** Streaming service tiles: the sheet's 5-column width, 16:9. */
private val AppleStreamingTileWidth: Dp = AppleTvSpacing.Col5
private val AppleStreamingTileHeight: Dp = AppleTvSpacing.Col5 * (9f / 16f)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppleStreamingServiceTile(
    service: StreamingService,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = remember { RoundedCornerShape(AppleTvRadius.Poster) }
    // Light tile for dark logos; dark tile for logos with white lettering (HBO Max).
    val tileColor = if (service.darkTile) Color(0xFF050507) else Color(0xFFF5F5F7)
    val edgeColor = if (service.darkTile) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.06f)

    Card(
        onClick = onClick,
        modifier = modifier.size(width = AppleStreamingTileWidth, height = AppleStreamingTileHeight),
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = tileColor,
            focusedContainerColor = tileColor
        ),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(0.5.dp, edgeColor), shape = shape),
            focusedBorder = Border.None
        ),
        scale = CardDefaults.scale(focusedScale = AppleTvFocusScale),
        glow = CardDefaults.glow(focusedGlow = AppleTvFocusGlow)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .padding(horizontal = 18.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = service.logoRes),
                contentDescription = service.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * "See All" card at the end of an Apple TV style row: poster sized glass card with an
 * arrow and a label; white with dark content when focused.
 */
@Composable
fun AppleSeeAllCard(
    label: String,
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val contentColor by animateColorAsState(
        targetValue = if (focused) OnWhite else Color.White,
        animationSpec = tween(durationMillis = 150),
        label = "appleSeeAllContent"
    )
    val badgeColor by animateColorAsState(
        targetValue = if (focused) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.18f),
        animationSpec = tween(durationMillis = 150),
        label = "appleSeeAllBadge"
    )
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    Card(
        onClick = onClick,
        modifier = modifier
            .size(width = width, height = height)
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
        shape = CardDefaults.shape(shape = shape),
        // Glass on the gray backdrop (kept dark enough for white text), white when focused.
        colors = CardDefaults.colors(
            containerColor = AppleTvColors.GlassOnGray,
            focusedContainerColor = AppleTvColors.FocusFill
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        scale = CardDefaults.scale(focusedScale = AppleTvFocusScale),
        glow = CardDefaults.glow(focusedGlow = AppleTvFocusGlow)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = label,
                color = contentColor,
                style = AppleTvType.Callout,
                textAlign = AppleTextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

/** One choice in the filter menu. [value] null means "all"; [count] is how many titles match it. */
@androidx.compose.runtime.Immutable
data class AppleFilterOption(val label: String, val value: String?, val count: Int? = null)

/** A titled group of choices in the filter menu (Type, Genre, Year). [options] starts with the "All" row. */
@androidx.compose.runtime.Immutable
data class AppleFilterSection(
    val key: String,
    val title: String,
    val options: List<AppleFilterOption>,
    val selectedValue: String?
)

private val AppleMenuFill = AppleTvColors.Surface2
private val AppleMenuDividerColor = AppleTvColors.Separator

/**
 * Glass pill for the top right of a See All page ("Filters  v", or the active choices such
 * as "Action · 2024"). Opens one dark tvOS-style menu with every section together (Type,
 * Genre, Year), each with an "All" row and how many titles match each choice. Picking a row
 * applies right away and keeps the menu open, so several filters can be set in one visit;
 * the menu closes with Back or with "Clear Filters".
 */
@Composable
fun AppleFilterMenuButton(
    label: String,
    sections: List<AppleFilterSection>,
    hasActiveFilter: Boolean,
    clearLabel: String,
    onSelect: (sectionKey: String, option: AppleFilterOption) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** Icon before the label; null for a plain "Label ⌄" pill. */
    leadingIcon: ImageVector? = Icons.Filled.Tune,
    /** Close the menu once a row is picked (single-choice menus). */
    closeOnSelect: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val firstRowRequester = remember { FocusRequester() }

    // Opening the menu puts focus on its first row.
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        repeat(8) {
            withFrameNanos { }
            if (runCatching { firstRowRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Box(modifier = modifier) {
        AppleGlassButton(
            onClick = { expanded = !expanded },
            shape = HeroPillShape,
            minWidth = 140.dp,
            modifier = Modifier.height(40.dp)
        ) { color ->
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = label,
                    color = color,
                    style = AppleTvType.Callout,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 260.dp, max = 300.dp)
                .heightIn(max = 420.dp),
            // Dark menu like tvOS pop-up menus (no colored edge), so the white names read clearly.
            shape = RoundedCornerShape(AppleTvRadius.Panel),
            containerColor = AppleMenuFill,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp
        ) {
            sections.forEachIndexed { sectionIndex, section ->
                if (sectionIndex > 0) AppleFilterMenuDivider()
                Text(
                    text = section.title,
                    color = AppleTvColors.LabelSecondary,
                    style = AppleTvType.Caption1,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 4.dp)
                )
                section.options.forEachIndexed { optionIndex, option ->
                    androidx.compose.runtime.key(section.key, option.value) {
                        AppleFilterMenuRow(
                            label = option.label,
                            count = option.count,
                            isSelected = option.value == section.selectedValue,
                            focusRequester = if (sectionIndex == 0 && optionIndex == 0) firstRowRequester else null,
                            onClick = {
                                onSelect(section.key, option)
                                if (closeOnSelect) expanded = false
                            }
                        )
                    }
                }
            }
            if (hasActiveFilter) {
                AppleFilterMenuDivider()
                AppleFilterMenuRow(
                    label = clearLabel,
                    leadingIcon = Icons.Filled.Close,
                    onClick = {
                        onClearAll()
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AppleFilterMenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(1.dp)
            .background(AppleMenuDividerColor)
    )
}

/** One row of the filter menu: check mark when chosen, name, count on the right; white pill on focus. */
@Composable
private fun AppleFilterMenuRow(
    label: String,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    count: Int? = null,
    leadingIcon: ImageVector? = null,
    focusRequester: FocusRequester? = null
) {
    var rowFocused by remember { mutableStateOf(false) }
    val textColor = if (rowFocused) OnWhite else AppleTvColors.Label
    val countColor = if (rowFocused) OnWhite.copy(alpha = 0.6f) else AppleTvColors.LabelSecondary
    DropdownMenuItem(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(HeroPillShape)
            .background(if (rowFocused) Color.White else Color.Transparent)
            .onFocusChanged { rowFocused = it.isFocused || it.hasFocus },
        leadingIcon = {
            Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                val icon = leadingIcon ?: if (isSelected) Icons.Filled.Check else null
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = textColor,
                    style = AppleTvType.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (count != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = count.toString(),
                        color = countColor,
                        style = AppleTvType.Caption1,
                        maxLines = 1
                    )
                }
            }
        },
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = textColor,
            leadingIconColor = textColor,
            trailingIconColor = textColor
        )
    )
}

/** Size of the horizontal (16:9) cards used by landscape rows. */
val AppleLandscapeCardWidth: Dp = AppleTvSpacing.Col4
val AppleLandscapeCardHeight: Dp = AppleTvSpacing.Col4 * (9f / 16f)

/**
 * Horizontal 16:9 card for landscape rows (e.g. Popular Series): wide artwork,
 * title logo (or the name) at the bottom left, optional rank number; grows with a
 * soft shadow when focused, like every other card.
 */
@Composable
fun AppleLandscapeCard(
    item: AppleMetaPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = AppleLandscapeCardWidth,
    height: Dp = AppleLandscapeCardHeight,
    cornerRadius: Dp = AppleTvRadius.Poster,
    rankNumber: Int? = null,
    isWatched: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (AppleMetaPreview) -> Unit = {},
    onLongPress: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val isPlaceholder = item.poster == PLACEHOLDER_IMAGE_URL
    val imageUrl = item.landscapePoster?.takeIf { it.isNotBlank() }
        ?: item.background?.takeIf { it.isNotBlank() }
        ?: item.poster
    val widthPx = remember(width, density) { with(density) { width.roundToPx() }.coerceAtLeast(1) }
    val heightPx = remember(height, density) { with(density) { height.roundToPx() }.coerceAtLeast(1) }
    val imageModel = remember(context, imageUrl, widthPx, heightPx) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(width = widthPx, height = heightPx)
            .crossfade(true)
            .build()
    }
    val logoModel = remember(context, item.logo, widthPx) {
        item.logo?.takeIf { it.isNotBlank() }?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(width = widthPx, height = with(density) { 40.dp.roundToPx() })
                .crossfade(true)
                .build()
        }
    }
    var logoFailed by remember(item.logo) { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()

    Card(
        onClick = {
            if (longPressTriggered) longPressTriggered = false else onClick()
        },
        modifier = modifier
            .size(width = width, height = height)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocus(item) }
            .onPreviewKeyEvent { event ->
                if (onLongPress == null) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                if (native.action == android.view.KeyEvent.ACTION_DOWN &&
                    native.keyCode == android.view.KeyEvent.KEYCODE_MENU
                ) {
                    longPressTriggered = true
                    onLongPress()
                    return@onPreviewKeyEvent true
                }
                if (longPressKeyTracker.handle(native, ::isAppleSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    if (native.action == android.view.KeyEvent.ACTION_UP) longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                if (native.action == android.view.KeyEvent.ACTION_UP && longPressTriggered &&
                    (isAppleSelectKey(native.keyCode) || native.keyCode == android.view.KeyEvent.KEYCODE_MENU)
                ) {
                    longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                false
            },
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = AppleTvColors.Surface2,
            focusedContainerColor = AppleTvColors.Surface2
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        scale = CardDefaults.scale(focusedScale = AppleTvFocusScale),
        glow = CardDefaults.glow(focusedGlow = AppleTvFocusGlow)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
        ) {
            if (!isPlaceholder && !imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (!isPlaceholder) {
                // Shade the lower part so the title reads on any artwork.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.45f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.72f)
                                )
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, end = 10.dp, bottom = 9.dp)
                ) {
                    if (logoModel != null && !logoFailed) {
                        AsyncImage(
                            model = logoModel,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.BottomStart,
                            onError = { logoFailed = true },
                            modifier = Modifier
                                .height(32.dp)
                                .widthIn(max = width * 0.6f)
                        )
                    } else {
                        Text(
                            text = item.name,
                            color = AppleTvColors.Label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = AppleTvType.Caption1.copy(shadow = AppleTvTextShadow)
                        )
                    }
                }
            }
            if (rankNumber != null) {
                Text(
                    text = rankNumber.toString(),
                    color = Color.White,
                    fontSize = 38.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Black,
                    style = TextStyle(
                        fontFamily = AppleTvType.Family,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.6f),
                            offset = Offset(0f, 2f),
                            blurRadius = 12f
                        )
                    ),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp)
                )
            }
            if (isWatched) {
                WatchedMarker(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = 8.dp)
                )
            }
        }
    }
}

private fun isAppleSelectKey(keyCode: Int): Boolean =
    keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
