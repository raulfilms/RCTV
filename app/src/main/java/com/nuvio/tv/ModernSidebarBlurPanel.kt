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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import java.util.Date

/*
 * Floating "glass" menu in the style of the tvOS 26 Apple TV app:
 * a rounded translucent gray card with the profile and clock on top,
 * every item drawn as a round icon badge plus label, and the focused
 * item turning into a full-width white pill with dark text.
 */

private val GlassPanelShape = RoundedCornerShape(40.dp)
private val ItemPillShape = RoundedCornerShape(percent = 50)
private val IconBadgeSize = 44.dp
private val ItemIconSize = 22.dp
private val ProfileAvatarSize = 48.dp

// Glass colors. With blur the gray is lighter and more see-through so the
// hero shows through; without blur (Android 11 and older) it stays a bit
// more solid so the text keeps enough contrast.
private val GlassTintBlur = Color(0xFF8A8A8E).copy(alpha = 0.58f)
private val GlassTintSolid = Color(0xFF5B5B60).copy(alpha = 0.94f)
private val GlassEdge = Color.White.copy(alpha = 0.22f)

private val PillFocused = Color(0xFFF2F2EE)
private val TextOnPill = Color(0xFF1C1C1E)
private val TextOnGlass = Color.White
private val BadgeOnGlass = Color.White.copy(alpha = 0.20f)
private val BadgeOnGlassSelected = Color.White.copy(alpha = 0.34f)
private val BadgeOnPill = Color.Black.copy(alpha = 0.08f)

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
    val expandedPanelBlurModifier = if (showPanelBlur) {
        Modifier.hazeEffect(state = sidebarHazeState) {
            blurRadius = NuvioTheme.effects.blurPanel * delayedBlurProgress
            noiseFactor = 0.04f * delayedBlurProgress
            inputScale = HazeInputScale.Fixed(0.66f)
        }
    } else {
        Modifier
    }
    val glassTint = if (blurEnabled) GlassTintBlur else GlassTintSolid

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .graphicsLayer {
                val p = sidebarExpandProgress
                alpha = p
                val s = 0.97f + (0.03f * p)
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .clip(GlassPanelShape)
            .then(expandedPanelBlurModifier)
            .background(color = glassTint, shape = GlassPanelShape)
            .border(width = 1.dp, color = GlassEdge, shape = GlassPanelShape)
            .padding(horizontal = 14.dp, vertical = 22.dp)
    ) {
        // Header: profile (or app wordmark) on the left, clock on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (showProfileSelector && activeProfileName.isNotEmpty()) {
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
                    BrandWordmark(
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .width(120.dp)
                            .height(32.dp),
                        alpha = sidebarLabelAlpha
                    )
                }
            }
            SidebarClock(
                modifier = Modifier
                    .padding(start = 8.dp, end = 10.dp)
                    .graphicsLayer { alpha = sidebarLabelAlpha }
            )
        }

        Spacer(modifier = Modifier.height(36.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
        color = TextOnGlass.copy(alpha = 0.9f),
        fontSize = 20.sp,
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
        targetValue = if (isFocused) PillFocused else Color.Transparent,
        animationSpec = fast,
        label = "sidebarItemPill"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isFocused) TextOnPill else TextOnGlass,
        animationSpec = fast,
        label = "sidebarItemContent"
    )
    val badgeColor by animateColorAsState(
        targetValue = when {
            isFocused -> BadgeOnPill
            selected -> BadgeOnGlassSelected
            else -> BadgeOnGlass
        },
        animationSpec = fast,
        label = "sidebarItemBadge"
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
        shape = CardDefaults.shape(shape = ItemPillShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(IconBadgeSize)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
                    .background(color = badgeColor, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                when {
                    icon != null -> Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(ItemIconSize)
                    )
                    iconRes != null -> Icon(
                        painter = rememberRawSvgPainter(iconRes),
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(ItemIconSize)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                color = contentColor,
                fontSize = 22.sp,
                fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
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
        targetValue = if (isFocused) TextOnPill else TextOnGlass,
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
        shape = CardDefaults.shape(shape = ItemPillShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatarCircle(
                name = profileName,
                colorHex = profileColorHex,
                size = ProfileAvatarSize,
                avatarImageUrl = profileAvatarImageUrl,
                imageCrossfade = false
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = profileName,
                color = textColor,
                fontSize = 22.sp,
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
