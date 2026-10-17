package com.nuvio.tv.ui.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.RctvAvatars

private val GalleryBackground = Color(0xFF16171D)
private val GalleryBackgroundTop = Color(0xFF262A3A)
private val GalleryAvatarSize = 118.dp

/**
 * "Choose Avatar", like Disney+'s: every avatar in rows by kind (Featured, Characters, Cats,
 * Robots...), big and round. Choosing one gives its address to the profile; Back closes it.
 */
@Composable
internal fun AvatarGallery(
    profileName: String,
    selectedUrl: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val listState = rememberLazyListState()
    val categories = RctvAvatars.categories
    // Open on the avatar the profile has (its row), else the first one.
    val startRow = categories.indexOfFirst { selectedUrl != null && selectedUrl in it.urls }.coerceAtLeast(0)
    val startUrl = selectedUrl?.takeIf { url -> categories.getOrNull(startRow)?.urls?.contains(url) == true }
        ?: categories.firstOrNull()?.urls?.firstOrNull()
    val startRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { listState.scrollToItem(startRow + 1) }
        repeat(12) {
            withFrameNanos { }
            if (runCatching { startRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GalleryBackground)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(0f to GalleryBackgroundTop, 0.5f to GalleryBackground, 1f to GalleryBackground)
                )
            )
            // Nothing behind it takes clicks.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 30.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "header") {
                Column(modifier = Modifier.padding(horizontal = 56.dp)) {
                    Text(
                        text = stringResource(R.string.profile_choose_avatar),
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (profileName.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.avatar_gallery_for, profileName),
                            color = Color(0xFF8E9099),
                            fontSize = 15.sp
                        )
                    }
                }
            }
            categories.forEachIndexed { rowIndex, category ->
                item(key = category.id) {
                    Column {
                        Text(
                            text = stringResource(category.titleRes),
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 56.dp, bottom = 12.dp)
                        )
                        val rowStartRequester = remember { FocusRequester() }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRestorer { rowStartRequester }
                        ) {
                            itemsIndexed(category.urls, key = { _, url -> url }) { index, url ->
                                val isStart = rowIndex == startRow && url == startUrl
                                GalleryAvatar(
                                    url = url,
                                    isSelected = url == selectedUrl,
                                    onClick = { onSelect(url) },
                                    modifier = Modifier
                                        .then(if (index == 0) Modifier.focusRequester(rowStartRequester) else Modifier)
                                        .then(if (isStart) Modifier.focusRequester(startRequester) else Modifier)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One avatar of the gallery: round; bigger with a white ring when focused; a check when it's the profile's. */
@Composable
private fun GalleryAvatar(
    url: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(contentAlignment = Alignment.BottomEnd) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(GalleryAvatarSize),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF2D3142),
                focusedContainerColor = Color(0xFF2D3142)
            ),
            border = ClickableSurfaceDefaults.border(
                border = if (isSelected) Border(BorderStroke(3.dp, Color.White.copy(alpha = 0.7f)), shape = CircleShape) else Border.None,
                focusedBorder = Border(BorderStroke(4.dp, Color.White), shape = CircleShape)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f)
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Avatars to show on the profile editor's button: a few of the Featured row. */
internal fun avatarPreviewUrls(count: Int): List<String> =
    RctvAvatars.categories.firstOrNull()?.urls.orEmpty().take(count)

/** How many avatars there are in all (not counting Featured, which repeats some). */
internal val avatarCount: Int get() = RctvAvatars.categories.drop(1).sumOf { it.paths.size }

@Composable
internal fun AvatarGalleryButtonContent(previewUrls: List<String>, title: String, subtitle: String, titleColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Box(modifier = Modifier.size(width = (44 + (previewUrls.size - 1).coerceAtLeast(0) * 30).dp, height = 44.dp)) {
            previewUrls.forEachIndexed { index, url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(start = (index * 30).dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2D3142))
                )
            }
        }
        Spacer(modifier = Modifier.size(16.dp))
        Column {
            Text(text = title, color = titleColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, fontSize = 13.sp, color = Color(0xFF8E9099))
        }
    }
}
