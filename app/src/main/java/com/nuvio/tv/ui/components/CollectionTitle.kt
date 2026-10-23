package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/**
 * RCTV: a collection's heading: the logo of its film series or series (John Wick, Dexter), like the
 * title pages show, instead of its name. While the logo is being looked for ([logoPending]) the
 * heading stays empty (no name first, then the logo); with no logo (or one that doesn't load) the
 * name shows as before.
 */
@Composable
fun CollectionTitle(
    title: String,
    logoUrl: String?,
    logoPending: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    logoHeight: Dp = 64.dp,
    logoMaxWidth: Dp = 380.dp
) {
    var failed by remember(logoUrl) { mutableStateOf(false) }
    val showLogo = logoUrl != null && !failed
    if (logoPending || showLogo) {
        Box(modifier = modifier.height(logoHeight), contentAlignment = Alignment.CenterStart) {
            if (showLogo) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onError = { failed = true },
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = logoMaxWidth)
                )
            }
        }
    } else {
        Text(
            text = title,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
    }
}
