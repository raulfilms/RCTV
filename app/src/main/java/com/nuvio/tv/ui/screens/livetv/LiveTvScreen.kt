package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.EpgProgram
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel = hiltViewModel(),
    onPlayChannel: (LiveTvChannel) -> Unit,
    onManageSources: () -> Unit = {},
    showBuiltInHeader: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Stalker channels carry a short-lived "cmd" token as streamUrl instead of a real URL, so it
    // must be resolved to a playable one right before navigating to the player. A no-op for
    // M3U/Xtream channels (see LiveTvViewModel.resolvePlaybackUrl).
    val resolvingPlayChannel: (LiveTvChannel) -> Unit = { channel ->
        coroutineScope.launch {
            val resolvedUrl = viewModel.resolvePlaybackUrl(channel)
            onPlayChannel(channel.copy(streamUrl = resolvedUrl))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.nav_live_tv),
                style = MaterialTheme.typography.headlineMedium,
                color = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            when {
                uiState.connection == null -> LiveTvSetup(uiState = uiState, viewModel = viewModel, onManageSources = onManageSources)
                uiState.isLoading -> LiveTvLoadingState()
                // A source-loading error only blocks the whole screen when it left no channels at
                // all to show; a partial failure (some sources loaded, others didn't) surfaces as
                // a banner above the still-usable channel browser instead (see below).
                uiState.error != null && uiState.channels.isEmpty() -> LiveTvErrorState(
                    message = uiState.error.orEmpty(),
                    onRetry = viewModel::retry,
                    onDisconnect = viewModel::disconnect
                )
                uiState.showGuide -> LiveTvGuide(
                    uiState = uiState,
                    onSelectProgram = viewModel::openProgramDetails,
                    onToggleGuide = { viewModel.setShowGuide(false) }
                )
                else -> LiveTvChannelBrowser(
                    uiState = uiState,
                    onSelectGroup = viewModel::selectGroup,
                    onSearchChange = viewModel::onChannelSearchChange,
                    onPlayChannel = resolvingPlayChannel,
                    onDisconnect = viewModel::disconnect,
                    onManageSources = onManageSources,
                    onToggleGuide = if (uiState.hasEpg) { { viewModel.setShowGuide(true) } } else null
                )
            }
        }

        val selectedProgram = uiState.selectedProgram
        val selectedChannel = uiState.selectedProgramChannel
        if (selectedProgram != null && selectedChannel != null) {
            LiveTvProgramDetailsOverlay(
                channel = selectedChannel,
                program = selectedProgram,
                nowMs = uiState.nowMs,
                onWatch = {
                    viewModel.dismissProgramDetails()
                    resolvingPlayChannel(selectedChannel)
                },
                onDismiss = viewModel::dismissProgramDetails
            )
        }
    }
}

@Composable
private fun LiveTvSetup(
    uiState: LiveTvUiState,
    viewModel: LiveTvViewModel,
    onManageSources: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(NuvioTheme.radii.md))
            .padding(NuvioTheme.spacing.lg)
    ) {
        Text(
            text = stringResource(R.string.livetv_add_playlist_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Text(
            text = stringResource(R.string.livetv_add_playlist_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        LiveTvGroupChip(
            label = stringResource(R.string.iptv_sources_manage_btn),
            selected = false,
            onClick = onManageSources
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            LiveTvGroupChip(
                label = stringResource(R.string.livetv_method_m3u),
                selected = uiState.setupMethod == LiveTvSetupMethod.M3U,
                onClick = { viewModel.selectSetupMethod(LiveTvSetupMethod.M3U) }
            )
            LiveTvGroupChip(
                label = stringResource(R.string.livetv_method_xtream),
                selected = uiState.setupMethod == LiveTvSetupMethod.XTREAM,
                onClick = { viewModel.selectSetupMethod(LiveTvSetupMethod.XTREAM) }
            )
            LiveTvGroupChip(
                label = stringResource(R.string.livetv_method_stalker),
                selected = uiState.setupMethod == LiveTvSetupMethod.STALKER,
                onClick = { viewModel.selectSetupMethod(LiveTvSetupMethod.STALKER) }
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        if (uiState.setupMethod == LiveTvSetupMethod.M3U) {
            LiveTvTextField(
                value = uiState.m3uUrlInput,
                onValueChange = viewModel::onM3uUrlChange,
                placeholder = stringResource(R.string.livetv_playlist_placeholder),
                keyboardType = KeyboardType.Uri
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            LiveTvTextField(
                value = uiState.m3uEpgInput,
                onValueChange = viewModel::onM3uEpgChange,
                placeholder = stringResource(R.string.livetv_epg_placeholder),
                keyboardType = KeyboardType.Uri
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Button(
                onClick = viewModel::connectM3u,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(text = stringResource(R.string.livetv_load_btn))
            }
        } else if (uiState.setupMethod == LiveTvSetupMethod.XTREAM) {
            LiveTvTextField(
                value = uiState.xtreamServerInput,
                onValueChange = viewModel::onXtreamServerChange,
                placeholder = stringResource(R.string.livetv_xtream_server_placeholder),
                keyboardType = KeyboardType.Uri
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            LiveTvTextField(
                value = uiState.xtreamUsernameInput,
                onValueChange = viewModel::onXtreamUsernameChange,
                placeholder = stringResource(R.string.livetv_xtream_username_placeholder),
                keyboardType = KeyboardType.Text
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            LiveTvTextField(
                value = uiState.xtreamPasswordInput,
                onValueChange = viewModel::onXtreamPasswordChange,
                placeholder = stringResource(R.string.livetv_xtream_password_placeholder),
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Button(
                onClick = viewModel::connectXtream,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(text = stringResource(R.string.livetv_connect_btn))
            }
        } else {
            LiveTvTextField(
                value = uiState.stalkerPortalInput,
                onValueChange = viewModel::onStalkerPortalChange,
                placeholder = stringResource(R.string.livetv_stalker_portal_placeholder),
                keyboardType = KeyboardType.Uri
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            LiveTvTextField(
                value = uiState.stalkerMacInput,
                onValueChange = viewModel::onStalkerMacChange,
                placeholder = stringResource(R.string.livetv_stalker_mac_placeholder),
                keyboardType = KeyboardType.Text
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Button(
                onClick = viewModel::connectStalker,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(text = stringResource(R.string.livetv_connect_btn))
            }
        }
    }
}

@Composable
private fun LiveTvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.padding(NuvioTheme.spacing.md)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                    decorationBox = { innerTextField ->
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }
    }
}

@Composable
private fun LiveTvLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.livetv_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@Composable
private fun LiveTvErrorState(
    message: String,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.LiveTv,
            contentDescription = null,
            tint = NuvioTheme.colors.TextTertiary,
            modifier = Modifier.height(48.dp)
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        Text(
            text = stringResource(R.string.livetv_error_loading),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                Text(text = stringResource(R.string.livetv_retry_btn))
            }
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(text = stringResource(R.string.livetv_change_playlist_btn))
            }
        }
    }
}

@Composable
private fun LiveTvChannelBrowser(
    uiState: LiveTvUiState,
    onSelectGroup: (String?) -> Unit,
    onSearchChange: (String) -> Unit,
    onPlayChannel: (LiveTvChannel) -> Unit,
    onDisconnect: () -> Unit,
    onManageSources: () -> Unit,
    onToggleGuide: (() -> Unit)?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.error != null) {
            Text(
                text = uiState.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).widthIn(max = 360.dp)) {
                LiveTvSearchField(value = uiState.channelSearchInput, onValueChange = onSearchChange)
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            LiveTvGroupChip(
                label = stringResource(R.string.iptv_sources_manage_btn),
                selected = false,
                onClick = onManageSources
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            LiveTvGroupChip(
                label = stringResource(R.string.livetv_change_playlist_btn),
                selected = false,
                onClick = onDisconnect
            )
            if (onToggleGuide != null) {
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                LiveTvGroupChip(
                    label = stringResource(R.string.livetv_guide_btn),
                    selected = false,
                    onClick = onToggleGuide,
                    icon = Icons.Default.CalendarViewDay
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        Row(modifier = Modifier.fillMaxSize()) {
            if (uiState.groups.isNotEmpty()) {
                LiveTvCategorySidebar(
                    groups = uiState.groups,
                    groupCounts = uiState.groupCounts,
                    totalCount = uiState.channels.size,
                    selectedGroup = uiState.selectedGroup,
                    onSelectGroup = onSelectGroup
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            }

            if (uiState.visibleChannels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.livetv_no_channels_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 180.dp),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.visibleChannels, key = { it.id }) { channel ->
                        LiveTvChannelCard(
                            channel = channel,
                            currentProgram = uiState.currentProgram(channel),
                            nowMs = uiState.nowMs,
                            onClick = { onPlayChannel(channel) }
                        )
                    }
                }
            }
        }
    }
}

/** Left-hand rail of channel categories, replacing a horizontal chip row so it stays usable with dozens of groups (multiple merged playlists/accounts easily produce that many). */
@Composable
private fun LiveTvCategorySidebar(
    groups: List<String>,
    groupCounts: Map<String, Int>,
    totalCount: Int,
    selectedGroup: String?,
    onSelectGroup: (String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.width(220.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        item {
            LiveTvCategoryRow(
                label = stringResource(R.string.livetv_group_all),
                count = totalCount,
                selected = selectedGroup == null,
                onClick = { onSelectGroup(null) }
            )
        }
        items(groups) { group ->
            LiveTvCategoryRow(
                label = group,
                count = groupCounts[group] ?: 0,
                selected = selectedGroup == group,
                onClick = { onSelectGroup(group) }
            )
        }
    }
}

@Composable
private fun LiveTvCategoryRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.FocusBackground else Color.Transparent,
            contentColor = if (selected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.sm)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}

@Composable
private fun LiveTvSearchField(value: String, onValueChange: (String) -> Unit) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.full)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.full)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.full)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.height(16.dp)
            )
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Box(modifier = Modifier.weight(1f)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                        cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                        decorationBox = { innerTextField ->
                            if (value.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.livetv_search_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioTheme.colors.TextTertiary
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTvGroupChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundElevated,
            contentColor = if (selected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.height(16.dp))
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LiveTvChannelCard(
    channel: LiveTvChannel,
    currentProgram: EpgProgram?,
    nowMs: Long,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = Modifier.fillMaxWidth().aspectRatio(1.55f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.LiveTv,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier.height(36.dp)
                    )
                }
            }
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (currentProgram != null) {
                Text(
                    text = currentProgram.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                LiveTvProgramProgressBar(progress = currentProgram.progressAt(nowMs))
            }
        }
    }
}

@Composable
private fun LiveTvProgramProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(50))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxSize()
                .background(NuvioTheme.colors.Primary, RoundedCornerShape(50))
        )
    }
}

private fun formatProgramTime(startMs: Long, endMs: Long): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return "${formatter.format(Date(startMs))} – ${formatter.format(Date(endMs))}"
}

@Composable
private fun LiveTvGuide(
    uiState: LiveTvUiState,
    onSelectProgram: (LiveTvChannel, EpgProgram) -> Unit,
    onToggleGuide: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.livetv_guide_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary
            )
            LiveTvGroupChip(
                label = stringResource(R.string.livetv_channels_btn),
                selected = false,
                onClick = onToggleGuide,
                icon = Icons.Default.GridView
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            items(uiState.channels, key = { it.id }) { channel ->
                LiveTvGuideRow(
                    channel = channel,
                    currentProgram = uiState.currentProgram(channel),
                    nextProgram = uiState.nextProgram(channel),
                    nowMs = uiState.nowMs,
                    onClickCurrent = { program -> onSelectProgram(channel, program) }
                )
            }
        }
    }
}

@Composable
private fun LiveTvGuideRow(
    channel: LiveTvChannel,
    currentProgram: EpgProgram?,
    nextProgram: EpgProgram?,
    nowMs: Long,
    onClickCurrent: (EpgProgram) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(NuvioTheme.radii.md))
            .padding(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(imageVector = Icons.Default.LiveTv, contentDescription = null, tint = NuvioTheme.colors.TextTertiary)
            }
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Text(
            text = channel.name,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp)
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))

        if (currentProgram != null) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onClickCurrent(currentProgram) }
            ) {
                Text(
                    text = currentProgram.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatProgramTime(currentProgram.startMs, currentProgram.endMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                LiveTvProgramProgressBar(progress = currentProgram.progressAt(nowMs))
            }
            if (nextProgram != null) {
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.livetv_next_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                    Text(
                        text = nextProgram.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.livetv_no_epg_data),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LiveTvProgramDetailsOverlay(
    channel: LiveTvChannel,
    program: EpgProgram,
    nowMs: Long,
    onWatch: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(NuvioTheme.radii.lg))
                .padding(NuvioTheme.spacing.xl)
                .clickable(enabled = false) {}
        ) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextTertiary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = program.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            Text(
                text = formatProgramTime(program.startMs, program.endMs),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            if (!program.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                Text(
                    text = program.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            if (program.isAiringAt(nowMs)) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                LiveTvProgramProgressBar(progress = program.progressAt(nowMs))
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Button(
                    onClick = onWatch,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        contentColor = NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.Primary
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                    Text(text = stringResource(R.string.livetv_watch_channel_btn))
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundElevated,
                        contentColor = NuvioTheme.colors.TextPrimary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = NuvioTheme.colors.Primary
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Text(text = stringResource(R.string.livetv_close_btn))
                }
            }
        }
    }
}
