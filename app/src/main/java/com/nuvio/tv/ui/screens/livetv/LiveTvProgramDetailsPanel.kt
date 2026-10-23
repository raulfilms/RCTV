package com.nuvio.tv.ui.screens.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.livetv.LiveProgramMatch
import com.nuvio.tv.data.livetv.LiveVodStatus
import com.nuvio.tv.data.livetv.LiveVodTarget
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.ui.components.AppleTvColors
import com.nuvio.tv.ui.components.AppleTvTextShadow
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** The details panel's share of the screen's width (it slides over the guide's right side). */
private const val PANEL_WIDTH_FRACTION = 0.40f

/**
 * A program's details, over the right side of the guide (a later show chosen in the guide, or one
 * on a channel's page): its movie or series' TMDB artwork with its logo, what the guide says about
 * this airing, then "Add to library" and "Browse episodes" (a series) or "View movie" (a movie),
 * which opens its page like any title in the app. A program TMDB doesn't have (a newscast, a
 * game) shows the guide's own picture and only what the guide says. Back closes it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveTvProgramDetailsOverlay(
    channel: LiveTvChannel,
    program: EpgProgram,
    nowMs: Long,
    tmdb: ProgramDetailsTmdb,
    onWatch: (() -> Unit)?,
    onAddToLibrary: () -> Unit,
    onOpenTitle: (LiveProgramMatch) -> Unit,
    onDismiss: () -> Unit,
    /**
     * RCTV: its movie or episode from its sources: "Watch From Beginning" on now, "Watch VOD" later
     * (only when TMDB has it and its sources do). Null: not offered here.
     */
    onWatchVod: ((LiveVodTarget) -> Unit)? = null,
    /**
     * RCTV: what's on now, chosen in the guide (it can be watched from the beginning): only
     * "Watch Live" and "Watch From Beginning", nothing else.
     */
    liveChoice: Boolean = false
) {
    BackHandler(onBack = onDismiss)
    val airingNow = program.isAiringAt(nowMs)
    val match = tmdb.match

    Box(modifier = Modifier.fillMaxSize()) {
        // The guide, dimmed, on the left; choosing it closes the panel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(PANEL_WIDTH_FRACTION)
                .background(AppleTvColors.Contrast)
        ) {
            ProgramDetailsArt(channel = channel, program = program, tmdb = tmdb)

            // What the guide says about this airing.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val details = listOfNotNull(
                    program.subtitle?.takeIf { it.isNotBlank() },
                    match?.let { if (it.isMovie) it.year?.toString() else null },
                    program.category?.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (details.isNotBlank()) {
                    Text(
                        text = details,
                        color = AppleTvColors.Label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${channel.name} · ${airingLabel(program)}",
                    color = AppleTvColors.LabelOnContrast,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
                val description = program.description?.takeIf { it.isNotBlank() } ?: match?.overview
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        color = AppleTvColors.Label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // The actions, focus starting on the first one.
            // RCTV: watching it from its sources (shown once TMDB has it; usable once a source is found).
            val vod = tmdb.vod?.takeIf { onWatchVod != null && it.status != LiveVodStatus.UNAVAILABLE }
            val vodAction = vod?.let { state ->
                val checking = state.status == LiveVodStatus.CHECKING
                DetailsAction(
                    icon = if (airingNow) Icons.Default.Replay else Icons.Default.OndemandVideo,
                    label = when {
                        checking -> stringResource(R.string.livetv_vod_looking)
                        airingNow -> stringResource(R.string.livetv_watch_from_beginning_btn)
                        else -> stringResource(R.string.livetv_watch_vod_btn)
                    },
                    onClick = { onWatchVod?.invoke(state.target) },
                    enabled = !checking
                )
            }
            val actions = buildList {
                // RCTV: what's on now, chosen in the guide: "Watch Live" and "Watch From Beginning" only.
                if (liveChoice && airingNow) {
                    if (onWatch != null) {
                        add(DetailsAction(Icons.Default.PlayArrow, stringResource(R.string.livetv_watch_live_btn), onWatch))
                    }
                    vodAction?.let { add(it) }
                    return@buildList
                }
                if (onWatch != null && airingNow) {
                    add(DetailsAction(Icons.Default.PlayArrow, stringResource(R.string.livetv_watch_channel_btn), onWatch))
                }
                when {
                    // Focusable meanwhile, so focus is in the panel from the start.
                    tmdb.isLoading -> add(
                        DetailsAction(Icons.Outlined.Info, stringResource(R.string.livetv_library_looking_up), onClick = {})
                    )
                    match != null -> {
                        add(
                            if (tmdb.inLibrary) {
                                DetailsAction(Icons.Default.Check, stringResource(R.string.livetv_details_in_library), onClick = {})
                            } else {
                                DetailsAction(Icons.Default.Add, stringResource(R.string.hero_add_to_library), onAddToLibrary, enabled = !tmdb.isAdding)
                            }
                        )
                        add(
                            DetailsAction(
                                Icons.Outlined.Info,
                                stringResource(if (match.isMovie) R.string.livetv_details_view_movie else R.string.livetv_details_browse_episodes),
                                onClick = { onOpenTitle(match) }
                            )
                        )
                        // Later: "Watch VOD" below the rest.
                        if (!airingNow) vodAction?.let { add(it) }
                    }
                    // Not on TMDB: its game (or the program itself) goes to the Library's Events.
                    else -> {
                        add(
                            if (tmdb.inLibrary) {
                                DetailsAction(Icons.Default.Check, stringResource(R.string.livetv_details_in_library), onClick = {})
                            } else {
                                DetailsAction(Icons.Default.Add, stringResource(R.string.hero_add_to_library), onAddToLibrary, enabled = !tmdb.isAdding)
                            }
                        )
                        add(DetailsAction(Icons.Default.Close, stringResource(R.string.livetv_close_btn), onDismiss))
                    }
                }
            }
            val firstAction = remember { FocusRequester() }
            // The actions change once TMDB answers: focus the first one again (not when the VOD
            // row shows or changes: focus stays where it is).
            val vodRows = if (vodAction != null && actions.contains(vodAction)) 1 else 0
            LaunchedEffect(tmdb.isLoading, match?.tmdbId, tmdb.game?.id, actions.size - vodRows) {
                repeat(6) {
                    withFrameNanos { }
                    if (runCatching { firstAction.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                actions.forEachIndexed { index, action ->
                    DetailsActionButton(
                        action = action,
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstAction) else Modifier)
                            // Focus stays in the panel (the guide is behind it).
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                                if (index == 0) up = FocusRequester.Cancel
                                if (index == actions.lastIndex) down = FocusRequester.Cancel
                            }
                    )
                }
            }
        }
    }
}

/**
 * The panel's 16:9 picture: the movie or series' TMDB backdrop with its logo (or name) over the
 * bottom, fading into the panel, like the app's title pages; the guide's own picture (or the
 * channel's logo) with the program's name when TMDB has none.
 */
@Composable
private fun ProgramDetailsArt(channel: LiveTvChannel, program: EpgProgram, tmdb: ProgramDetailsTmdb) {
    val art = tmdb.art?.takeIf { it.hasArt }
    var logoFailed by remember(art?.logo) { mutableStateOf(false) }
    // A plain backdrop with the logo laid over it; a backdrop with the title on it as is.
    val plainBackdrop = art?.backdrop
    val image = plainBackdrop ?: art?.titledBackdrop ?: program.imageUrl?.takeIf { it.isNotBlank() }
    val titleOnImage = plainBackdrop == null && art?.titledBackdrop != null
    val name = tmdb.match?.name ?: cleanProgramTitle(program.title)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(AppleTvColors.ContrastHigh)
    ) {
        when {
            image != null -> AsyncImage(
                model = image,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            !channel.logoUrl.isNullOrBlank() && !tmdb.isLoading -> AsyncImage(
                model = channel.logoUrl,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 56.dp, vertical = 32.dp)
            )
        }
        if (!titleOnImage) {
            // Fades into the panel so the title reads and the picture blends in.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.5f to Color.Transparent,
                                1f to AppleTvColors.Contrast
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                val logo = art?.logo
                if (logo != null && !logoFailed) {
                    AsyncImage(
                        model = logo,
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomCenter,
                        onError = { logoFailed = true },
                        modifier = Modifier
                            .height(46.dp)
                            .widthIn(max = 240.dp)
                    )
                } else {
                    Text(
                        text = name,
                        color = AppleTvColors.Label,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = androidx.compose.ui.text.TextStyle(shadow = AppleTvTextShadow)
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}

private data class DetailsAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true
)

/** A row in the panel's list of actions: white when focused, like the app's menus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailsActionButton(action: DetailsAction, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = AppleTvColors.Label,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = AppleTvColors.LabelOnContrast
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = action.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** When the program airs: "Today, 2:30 PM", "Tomorrow, 9:00 AM" or "Wed, Oct 14, 2:30 PM". */
@Composable
private fun airingLabel(program: EpgProgram): String {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(program.startMs))
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(program.startMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> stringResource(R.string.livetv_details_today, time)
        today.plusDays(1) -> stringResource(R.string.livetv_details_tomorrow, time)
        else -> SimpleDateFormat("EEE, MMM d, h:mm a", Locale.getDefault()).format(Date(program.startMs))
    }
}
