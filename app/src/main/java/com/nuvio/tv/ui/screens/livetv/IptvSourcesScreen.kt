package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LiveTvConnection
import com.nuvio.tv.domain.model.LiveTvSource
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Lists every configured IPTV source (M3U playlist, Xtream account, Stalker portal) and lets the
 * user add another, toggle one on/off, or remove it - the multi-source management screen the
 * engine (phase 1 step 3) and the merged-loading logic (phase 2 step 1) were built to support, on
 * top of the single-source setup flow [LiveTvScreen] already offers for a first connection.
 */
@Composable
fun IptvSourcesScreen(
    viewModel: IptvSourcesViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onBackPress,
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.full))
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.padding(NuvioTheme.spacing.sm).height(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.iptv_sources_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        if (uiState.sources.isEmpty() && !uiState.isAdding) {
            Text(
                text = stringResource(R.string.iptv_sources_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        } else if (uiState.sources.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                items(uiState.sources, key = { it.id }) { source ->
                    IptvSourceRow(
                        source = source,
                        onToggle = { enabled -> viewModel.setSourceEnabled(source.id, enabled) },
                        onRemove = { viewModel.removeSource(source.id) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        }

        if (uiState.isAdding) {
            IptvSourceAddForm(uiState = uiState, viewModel = viewModel)
        } else {
            Button(
                onClick = viewModel::startAdding,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(text = stringResource(R.string.iptv_sources_add_btn))
            }
        }
    }
}

@Composable
private fun IptvSourceRow(
    source: LiveTvSource,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(NuvioTheme.radii.md))
            .padding(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = connectionSummary(source.connection),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Switch(
            checked = source.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NuvioTheme.colors.Primary,
                checkedTrackColor = NuvioTheme.colors.FocusBackground
            )
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
        Surface(
            onClick = onRemove,
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.full))
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.iptv_sources_remove_btn),
                tint = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(NuvioTheme.spacing.sm).height(18.dp)
            )
        }
    }
}

private fun connectionSummary(connection: LiveTvConnection): String = when (connection) {
    is LiveTvConnection.M3u -> "M3U · ${connection.playlistUrl}"
    is LiveTvConnection.Xtream -> "Xtream · ${connection.username}@${connection.serverUrl}"
    is LiveTvConnection.Stalker -> "Stalker · ${connection.macAddress} @ ${connection.portalUrl}"
}

@Composable
private fun IptvSourceAddForm(
    uiState: IptvSourcesUiState,
    viewModel: IptvSourcesViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(NuvioTheme.radii.md))
            .padding(NuvioTheme.spacing.lg)
    ) {
        Text(
            text = stringResource(R.string.iptv_sources_add_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            IptvSourceTypeChip(
                label = stringResource(R.string.livetv_method_m3u),
                selected = uiState.addType == IptvSourceType.M3U,
                onClick = { viewModel.selectAddType(IptvSourceType.M3U) }
            )
            IptvSourceTypeChip(
                label = stringResource(R.string.livetv_method_xtream),
                selected = uiState.addType == IptvSourceType.XTREAM,
                onClick = { viewModel.selectAddType(IptvSourceType.XTREAM) }
            )
            IptvSourceTypeChip(
                label = stringResource(R.string.livetv_method_stalker),
                selected = uiState.addType == IptvSourceType.STALKER,
                onClick = { viewModel.selectAddType(IptvSourceType.STALKER) }
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        IptvSourcesTextField(
            value = uiState.nameInput,
            onValueChange = viewModel::onNameChange,
            placeholder = stringResource(R.string.iptv_sources_name_placeholder),
            keyboardType = KeyboardType.Text
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

        when (uiState.addType) {
            IptvSourceType.M3U -> {
                IptvSourcesTextField(
                    value = uiState.m3uUrlInput,
                    onValueChange = viewModel::onM3uUrlChange,
                    placeholder = stringResource(R.string.livetv_playlist_placeholder),
                    keyboardType = KeyboardType.Uri
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                IptvSourcesTextField(
                    value = uiState.m3uEpgInput,
                    onValueChange = viewModel::onM3uEpgChange,
                    placeholder = stringResource(R.string.livetv_epg_placeholder),
                    keyboardType = KeyboardType.Uri
                )
            }
            IptvSourceType.XTREAM -> {
                IptvSourcesTextField(
                    value = uiState.xtreamServerInput,
                    onValueChange = viewModel::onXtreamServerChange,
                    placeholder = stringResource(R.string.livetv_xtream_server_placeholder),
                    keyboardType = KeyboardType.Uri
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                IptvSourcesTextField(
                    value = uiState.xtreamUsernameInput,
                    onValueChange = viewModel::onXtreamUsernameChange,
                    placeholder = stringResource(R.string.livetv_xtream_username_placeholder),
                    keyboardType = KeyboardType.Text
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                IptvSourcesTextField(
                    value = uiState.xtreamPasswordInput,
                    onValueChange = viewModel::onXtreamPasswordChange,
                    placeholder = stringResource(R.string.livetv_xtream_password_placeholder),
                    keyboardType = KeyboardType.Password,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
            IptvSourceType.STALKER -> {
                IptvSourcesTextField(
                    value = uiState.stalkerPortalInput,
                    onValueChange = viewModel::onStalkerPortalChange,
                    placeholder = stringResource(R.string.livetv_stalker_portal_placeholder),
                    keyboardType = KeyboardType.Uri
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                IptvSourcesTextField(
                    value = uiState.stalkerMacInput,
                    onValueChange = viewModel::onStalkerMacChange,
                    placeholder = stringResource(R.string.livetv_stalker_mac_placeholder),
                    keyboardType = KeyboardType.Text
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            Button(
                onClick = viewModel::saveNewSource,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(text = stringResource(R.string.iptv_sources_save_btn))
            }
            Button(
                onClick = viewModel::cancelAdding,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun IptvSourceTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
        )
    }
}

@Composable
private fun IptvSourcesTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation = VisualTransformation.None
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
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(NuvioTheme.colors.Primary),
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
