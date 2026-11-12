package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.rctvcontrol.RctvBanner
import com.nuvio.tv.core.rctvcontrol.RctvBannerAction
import com.nuvio.tv.ui.components.AppleGlassButton
import com.nuvio.tv.ui.components.AppleTvContentStart
import com.nuvio.tv.ui.components.AppleTvSpacing
import com.nuvio.tv.ui.components.AppleTvType

private val BannerHeight = 270.dp
private val BannerInk = Color(0xFF050810)
private val ButtonShape = RoundedCornerShape(10.dp)

/** Home's key for a banner's item in the list. */
internal fun rctvBannerKey(banner: RctvBanner): String = "rctv_banner_${banner.id}"

/**
 * RCTV Control: a banner between Home's rows, like Prime Video's: the owner's picture across the
 * whole width (darker on the left), a big title, a line of text, one button and a small note. A
 * banner without a picture shows the team or league logos on the right instead.
 */
@Composable
internal fun RctvHomeBanner(
    banner: RctvBanner,
    focusRequester: FocusRequester,
    /** Focus goes to the button (coming back to Home onto this banner). */
    requestFocus: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(requestFocus) {
        if (!requestFocus) return@LaunchedEffect
        repeat(4) {
            withFrameNanos { }
            if (runCatching { focusRequester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Home's rows are 26dp apart already.
            .padding(vertical = 6.dp)
            .height(BannerHeight)
            .background(Color(0xFF0B1220))
    ) {
        if (banner.imageUrl != null) {
            AsyncImage(
                model = banner.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.CenterEnd,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            BannerLogos(banner.action, Modifier.align(Alignment.CenterEnd).padding(end = 72.dp))
        }
        // The left side darker, so the title reads on any picture.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to BannerInk.copy(alpha = 0.95f),
                        0.35f to BannerInk.copy(alpha = 0.8f),
                        0.7f to Color.Transparent
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.55f)
                .padding(start = AppleTvContentStart, end = AppleTvSpacing.Space3),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = banner.title,
                color = Color.White,
                fontSize = when {
                    banner.title.length <= 12 -> 50.sp
                    banner.title.length <= 22 -> 40.sp
                    else -> 30.sp
                },
                lineHeight = when {
                    banner.title.length <= 12 -> 52.sp
                    banner.title.length <= 22 -> 42.sp
                    else -> 33.sp
                },
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (banner.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(AppleTvSpacing.Space2))
                Text(
                    text = banner.subtitle,
                    color = Color.White.copy(alpha = 0.88f),
                    style = AppleTvType.Callout,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            AppleGlassButton(
                onClick = onClick,
                shape = ButtonShape,
                minWidth = 150.dp,
                modifier = Modifier
                    .height(40.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused || it.hasFocus) onFocused() }
            ) { color ->
                Text(
                    text = banner.button.ifBlank { stringResource(defaultButton(banner.action)) },
                    color = color,
                    style = AppleTvType.Callout,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 22.dp)
                )
            }
            if (banner.note.isNotBlank()) {
                Spacer(modifier = Modifier.height(AppleTvSpacing.Space2))
                Text(
                    text = banner.note,
                    color = Color.White.copy(alpha = 0.75f),
                    style = AppleTvType.Caption1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun defaultButton(action: RctvBannerAction): Int = when (action) {
    is RctvBannerAction.Collection -> R.string.rctv_banner_button_collection
    is RctvBannerAction.Game -> R.string.rctv_banner_button_game
    is RctvBannerAction.Team -> R.string.rctv_banner_button_team
    is RctvBannerAction.League -> R.string.rctv_banner_button_league
    is RctvBannerAction.Channel -> R.string.rctv_banner_button_channel
}

/** The logos on a banner without a picture: the two teams of a game, a team's or a league's. */
@Composable
private fun BannerLogos(action: RctvBannerAction, modifier: Modifier = Modifier) {
    val logos = when (action) {
        is RctvBannerAction.Game -> listOfNotNull(action.away?.logoUrl, action.home?.logoUrl)
        is RctvBannerAction.Team -> listOfNotNull(action.logoUrl)
        is RctvBannerAction.League -> listOfNotNull(action.logoUrl)
        else -> emptyList()
    }
    if (logos.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        logos.forEach { logo ->
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(140.dp)
            )
        }
    }
}
