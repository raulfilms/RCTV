package com.nuvio.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvRadius
import com.nuvio.tv.ui.components.AppleTvType
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import java.util.Date

/*
 * The side menu, drawn like the Apple TV app (tvOS 26, "Apple TV 2026 Style" sheet):
 *  - a floating Liquid Glass card (the content behind, strongly blurred, with a
 *    light glass layer) that ends after its last item; on TVs without blur it is
 *    a solid smoky gray so the labels always read;
 *  - the profile photo and name at the top, the time on the right;
 *  - every item is a light round badge with a white glyph and a white label;
 *  - the focused item becomes a full-width white pill with dark text, and its
 *    badge turns light gray with a dark glyph.
 */

// Sizes measured against the Apple TV (tvOS 26) menu at 1080p: a compact card
// about 200dp wide, an item every ~41dp, small badges and 14sp labels.
private val PanelShape = RoundedCornerShape(AppleTvRadius.Panel)
private val PillShape = AppleTvRadius.Pill

private val ItemHeight = 36.dp
private val ItemSpacing = 5.dp
private val BadgeSize = 24.dp
private val GlyphSize = 14.dp
private val AvatarSize = 26.dp

// Without blur: smoky mid gray like the Apple TV menu. Darker than a "light gray" so
// white labels stay crisp on TVs that push grays toward blue/lilac.
private val PanelTop = Color(0xFF58585C)
private val PanelBottom = Color(0xFF6A6A6E)
// Light rim of the glass (white, not a color).
private val PanelEdge = Color.White.copy(alpha = 0.16f)
// Blur strength of the Liquid Glass (the sheet's ~40 px at 1080p).
private val GlassBlurRadius = 20.dp

private val PillFocused = AppleTvColors.FocusFill
private val LabelOnGlass = AppleTvColors.Label
private val LabelOnPill = AppleTvColors.FocusLabel
private val BadgeOnGlass = Color.White.copy(alpha = 0.20f)
private val BadgeOnPill = Color(0xFFDCDCE0)
private val GlyphOnPill = Color(0xFF3A3A3C)

@Composable
internal fun ModernSidebarBlurPanel(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    keepSidebarFocusDuringCollapse: Boolean,
    sidebarLabelAlpha: Float,
    sidebarIconScale: Float,
    sidebarExpandProgress: Float,
    isSidebarExpanded: Boolean,
    sidebarCollapsePending: Boolean,
    blurEnabled: Boolean,
    sidebarHazeState: HazeState,
    panelShape: RoundedCornerShape,
    drawerItemFocusRequesters: Map<String, FocusRequester>,
    onDrawerItemFocused: (Int) -> Unit,
    onDrawerItemClick: (String) -> Unit,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit
) {
    val delayedBlurProgress =
        ((sidebarExpandProgress - 0.34f) / 0.66f).coerceIn(0f, 1f)
    val showPanelBlur = blurEnabled &&
        isSidebarExpanded &&
        !sidebarCollapsePending &&
        delayedBlurProgress > 0f
    val blurModifier = if (showPanelBlur) {
        Modifier.hazeEffect(state = sidebarHazeState) {
            blurRadius = GlassBlurRadius * delayedBlurProgress
            noiseFactor = 0f
            inputScale = HazeInputScale.Fixed(0.66f)
        }
    } else {
        Modifier
    }
    // Liquid Glass once the blur is on: the solid gray fades out as the blur fades in.
    // Without blur the gray stays fully opaque, so nothing sharp shows through the menu.
    val glassProgress = if (showPanelBlur) delayedBlurProgress else 0f
    val panelBrush = remember {
        Brush.linearGradient(colors = listOf(PanelTop, PanelBottom))
    }

    Column(
        modifier = Modifier
            .graphicsLayer {
                val p = sidebarExpandProgress
                alpha = p
                val s = 0.97f + (0.03f * p)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .clip(PanelShape)
            .then(blurModifier)
            .background(brush = panelBrush, shape = PanelShape, alpha = 1f - glassProgress)
            // Glass: a dark layer so the white labels read on the gray backdrop and on
            // artwork, then the sheet's white glass on top.
            .background(
                color = AppleTvColors.GlassShade.copy(alpha = AppleTvColors.GlassShade.alpha * glassProgress),
                shape = PanelShape
            )
            .background(
                color = AppleTvColors.Glass.copy(alpha = AppleTvColors.Glass.alpha * glassProgress),
                shape = PanelShape
            )
            .border(width = 1.dp, color = PanelEdge, shape = PanelShape)
            .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 12.dp)
    ) {
        // Header: profile photo and name, time on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (activeProfileName.isNotEmpty()) {
                    SidebarProfileItem(
                        profileName = activeProfileName,
                        profileColorHex = activeProfileColorHex,
                        profileAvatarImageUrl = activeProfileAvatarImageUrl,
                        focusEnabled = keepSidebarFocusDuringCollapse,
                        labelAlpha = sidebarLabelAlpha,
                        onFocusChanged = { focused ->
                            if (focused) onDrawerItemFocused(drawerItems.size)
                        },
                        onClick = onSwitchProfile,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // No profile yet: a plain person badge in the same spot.
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(AvatarSize)
                            .clip(CircleShape)
                            .background(BadgeOnGlass),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            SidebarClock(
                modifier = Modifier
                    .padding(start = 6.dp, end = 10.dp)
                    .graphicsLayer { alpha = sidebarLabelAlpha }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ItemSpacing)
        ) {
            drawerItems.forEachIndexed { index, item ->
                key(item.route) {
                    SidebarNavigationItem(
                        label = item.label,
                        iconRes = item.iconRes,
                        icon = item.icon,
                        selected = selectedDrawerRoute == item.route,
                        focusEnabled = keepSidebarFocusDuringCollapse,
                        labelAlpha = sidebarLabelAlpha,
                        iconScale = sidebarIconScale,
                        onFocusChanged = {
                            if (it) {
                                onDrawerItemFocused(index)
                            }
                        },
                        onClick = { onDrawerItemClick(item.route) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(drawerItemFocusRequesters.getValue(item.route))
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Follows the device's 12/24-hour setting.
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            val msToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(msToNextMinute)
        }
    }
    Text(
        text = timeFormat.format(now),
        color = AppleTvColors.Label,
        style = AppleTvType.Caption1,
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
private fun SidebarNavigationItem(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    focusEnabled: Boolean,
    labelAlpha: Float,
    iconScale: Float,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val fast = tween<Color>(durationMillis = NuvioMotion.tokens.durations.fast)

    val pillColor by animateColorAsState(
        targetValue = if (isFocused) PillFocused else Color.Transparent,
        animationSpec = fast,
        label = "sidebarItemPill"
    )
    val labelColor by animateColorAsState(
        targetValue = if (isFocused) LabelOnPill else LabelOnGlass,
        animationSpec = fast,
        label = "sidebarItemLabel"
    )
    val badgeColor by animateColorAsState(
        targetValue = if (isFocused) BadgeOnPill else BadgeOnGlass,
        animationSpec = fast,
        label = "sidebarItemBadge"
    )
    val glyphColor by animateColorAsState(
        targetValue = if (isFocused) GlyphOnPill else Color.White,
        animationSpec = fast,
        label = "sidebarItemGlyph"
    )
    val itemScale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1f,
        animationSpec = tween(
            durationMillis = NuvioMotion.tokens.durations.fast,
            easing = NuvioMotion.tokens.easings.standard
        ),
        label = "sidebarItemScale"
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .height(ItemHeight)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
                transformOrigin = TransformOrigin.Center
            }
            .onFocusChanged {
                isFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusProperties { canFocus = focusEnabled },
        colors = CardDefaults.colors(
            containerColor = pillColor,
            focusedContainerColor = pillColor,
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border.None
        ),
        shape = CardDefaults.shape(shape = PillShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ItemHeight)
                .padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(BadgeSize)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                when {
                    icon != null -> Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = glyphColor,
                        modifier = Modifier.size(GlyphSize)
                    )
                    iconRes != null -> Icon(
                        painter = rememberRawSvgPainter(iconRes),
                        contentDescription = null,
                        tint = glyphColor,
                        modifier = Modifier.size(GlyphSize)
                    )
                }
            }
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = label,
                color = labelColor,
                style = AppleTvType.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = labelAlpha }
            )
        }
    }
}

@Composable
private fun SidebarProfileItem(
    profileName: String,
    profileColorHex: String,
    profileAvatarImageUrl: String?,
    focusEnabled: Boolean,
    labelAlpha: Float,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val fast = tween<Color>(durationMillis = NuvioMotion.tokens.durations.fast)
    val pillColor by animateColorAsState(
        targetValue = if (isFocused) PillFocused else Color.Transparent,
        animationSpec = fast,
        label = "sidebarProfilePill"
    )
    val textColor by animateColorAsState(
        targetValue = if (isFocused) LabelOnPill else LabelOnGlass,
        animationSpec = fast,
        label = "sidebarProfileText"
    )
    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged {
                isFocused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusProperties { canFocus = focusEnabled },
        colors = CardDefaults.colors(
            containerColor = pillColor,
            focusedContainerColor = pillColor,
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border.None
        ),
        shape = CardDefaults.shape(shape = PillShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatarCircle(
                name = profileName,
                colorHex = profileColorHex,
                size = AvatarSize,
                avatarImageUrl = profileAvatarImageUrl,
                imageCrossfade = false
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = profileName,
                color = textColor,
                style = AppleTvType.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = labelAlpha }
            )
        }
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { NuvioTheme.spacing.xl.roundToPx() }
    return rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current)
            .data(rawIconRes)
            .size(sizePx)
            .build()
    )
}
