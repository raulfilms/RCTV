package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.R
import com.nuvio.tv.core.titlestatus.TitleStatus
import com.nuvio.tv.domain.model.MetaPreview

/** RCTV: what the profile watching has done with each title (see [TitleStatus]). */
val LocalTitleStatus = compositionLocalOf { TitleStatus() }

private val EyeBlue = Color(0xFF1E7BFF)
private val ImdbYellow = Color(0xFFF5C518)
private val ImdbDark = Color(0xFF121212)

/**
 * RCTV: the marks at the top right of every movie and series thumbnail:
 * - a blue eye: watched all the way through;
 * - a black check on white: in the app's Library;
 * - a yellow check (IMDb's yellow): in the IMDb watchlist.
 * [watched] adds what a screen already knows (watched just now).
 */
@Composable
fun TitleStatusBadges(
    item: MetaPreview?,
    watched: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 21.dp
) {
    val marks = item?.let { LocalTitleStatus.current.of(it) }
    val isWatched = watched || marks?.watched == true
    val inLibrary = marks?.inLibrary == true
    val inImdb = marks?.inImdbList == true
    if (!isWatched && !inLibrary && !inImdb) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (inImdb) {
            StatusDot(Icons.Default.Check, ImdbYellow, ImdbDark, stringResource(R.string.title_status_imdb), size)
        }
        if (inLibrary) {
            StatusDot(Icons.Default.Check, Color.Black, Color.White, stringResource(R.string.title_status_library), size)
        }
        if (isWatched) {
            StatusDot(Icons.Default.Visibility, EyeBlue, Color.White, stringResource(R.string.episodes_cd_watched), size)
        }
    }
}

@Composable
private fun StatusDot(icon: ImageVector, tint: Color, background: Color, description: String, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(6.dp, shape = CircleShape, spotColor = Color.Transparent)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            tint = tint,
            contentDescription = description,
            modifier = Modifier.size(size * 0.72f)
        )
    }
}
