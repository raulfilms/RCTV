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
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import java.util.Date

/*
 * The side menu, drawn like the Apple TV app (tvOS 26):
 *  - a floating, neutral gray "glass" card with big rounded corners that ends
 *    after its last item (it does not run to the bottom of the screen);
 *  - the profile photo and name at the top, the time on the right;
 *  - every item is a light round badge with a white glyph and a white label;
 *  - the focused item becomes a full-width white pill with dark text, and its
 *    badge turns light gray with a dark glyph.
 */

private val PanelShape = RoundedCornerShape(36.dp)
private val PillShape = RoundedCornerShape(percent = 50)

private val ItemHeight = 46.dp
private val ItemSpacing = 6.dp
private val BadgeSize = 34.dp
private val GlyphSize = 19.dp
private val AvatarSize = 38.dp

// Neutral gray glass. Without a real blur (Android 11 and older) it is fully
// opaque, so the picture behind never makes the labels hard to read.
private val PanelTop = Color(0xFF77777C)
private val PanelBottom = Color(0xFF8E8E93)
private val PanelEdge = Color.White.copy(alpha = 0.22f)

private val PillFocused = Color(0xFFF2F2F2)
private val PillSelected = Color.White.copy(alpha = 0.14f)
private val LabelOnGlass = Color.White
private val LabelOnPill = Color(0xFF2C2C2E)
private val BadgeOnGlass = Color.White.copy(alpha = 0.24f)
private val BadgeOnPill = Color(0xFFE1E1E6)
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
            blurRadius = NuvioTheme.effects.blurPanel * 1.4f * delayedBlurProgress
            noiseFactor = 0f
            inputScale = HazeInputScale.Fixed(0.66f)
        }
    } else {
        Modifier
    }
    // Fully opaque without blur, so nothing behind the menu shows through.
    val panelAlpha = if (blurEnabled) 0.86f else 1f
    val panelBrush = remember(panelAlpha) {
        Brush.linearGradient(
            colors = listOf(
                PanelTop.copy(alpha = panelAlpha),
                PanelBottom.copy(alpha = panelAlpha)
            )
        )
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
            .background(brush = panelBrush, shape = PanelShape)
            .border(width = 1.dp, color = PanelEdge, shape = PanelShape)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp)
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
                        focusEnabled = keepSidebarFocusDuringCollapse && showProfileSelector,
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
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            SidebarClock(
                modifier = Modifier
                    .padding(start = 8.dp, end = 12.dp)
                    .graphicsLayer { alpha = sidebarLabelAlpha }
            )
        }

        Spacer(modifier = Modifier.height(30.dp))

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
        color = Color.White.copy(alpha = 0.95f),
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
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
        targetValue = when {
            isFocused -> PillFocused
            selected -> PillSelected
            else -> Color.Transparent
        },
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
        targetValue = if (isFocused) 1.02f else 1f,
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
                .padding(start = 6.dp, end = 14.dp),
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
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                color = labelColor,
                fontSize = 18.sp,
                fontWeight = if (isFocused) FontWeight.Medium else FontWeight.SemiBold,
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
                .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatarCircle(
                name = profileName,
                colorHex = profileColorHex,
                size = AvatarSize,
                avatarImageUrl = profileAvatarImageUrl,
                imageCrossfade = false
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = profileName,
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
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
