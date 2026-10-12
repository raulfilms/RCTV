package com.nuvio.tv.ui.screens.studios

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.StreamingService
import com.nuvio.tv.domain.model.StreamingServiceTarget
import com.nuvio.tv.ui.components.AppleStreamingServiceTile
import com.nuvio.tv.ui.components.AppleTvColors

/** Tiles per row on a studio group page: four on top, four below. */
private const val STUDIO_GROUP_COLUMNS = 4

/** Bigger than Home's tiles, since the page holds only these. */
private val StudioTileWidth: Dp = 200.dp
private val StudioTileHeight: Dp = StudioTileWidth * (9f / 16f)
private val StudioTileGap: Dp = 24.dp

/** The Home tile's logo above the tiles. */
private val StudioGroupLogoHeight: Dp = 72.dp

/**
 * A page of studio tiles opened from a Home tile (Independent Film Company: Bleecker Street, IFC Films,
 * Magnolia Pictures, Annapurna Pictures, Millennium Media, NEON, Plan B, Roadside Attractions): the Home
 * tile's logo on top and the studios' tiles in rows of four, centered. Each tile opens that studio's page.
 */
@Composable
fun StudioGroupScreen(
    onOpenStudio: (studio: StreamingService, target: StreamingServiceTarget) -> Unit,
    viewModel: StudioGroupViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val studios: List<StreamingService> by viewModel.studios.collectAsState()
    val owner = viewModel.owner
    val requesters = remember(studios.size) { List(studios.size) { FocusRequester() } }
    // The tile that was opened, so coming back from a studio's page puts focus back on it.
    var focusedIndex by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(studios.size) {
        val target = requesters.getOrNull(focusedIndex) ?: requesters.firstOrNull() ?: return@LaunchedEffect
        repeat(6) {
            withFrameNanos { }
            if (runCatching { target.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppleTvColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (owner != null) {
                Image(
                    painter = painterResource(id = owner.logoRes),
                    contentDescription = owner.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(StudioGroupLogoHeight)
                        .widthIn(max = 320.dp)
                )
                Spacer(modifier = Modifier.height(40.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(StudioTileGap)) {
                studios.chunked(STUDIO_GROUP_COLUMNS).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(StudioTileGap)) {
                        row.forEachIndexed { columnIndex, studio ->
                            val index = rowIndex * STUDIO_GROUP_COLUMNS + columnIndex
                            AppleStreamingServiceTile(
                                service = studio,
                                onClick = {
                                    val target = studio.target
                                    if (target != null) {
                                        onOpenStudio(studio, target)
                                    } else {
                                        // Not looked up yet (no connection?): try again and say so.
                                        viewModel.resolve()
                                        Toast.makeText(context, R.string.streaming_service_unavailable, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .size(width = StudioTileWidth, height = StudioTileHeight)
                                    .focusRequester(requesters[index])
                                    .onFocusChanged { if (it.isFocused) focusedIndex = index }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
