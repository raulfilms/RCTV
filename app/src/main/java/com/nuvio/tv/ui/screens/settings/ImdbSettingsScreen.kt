@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.text.format.DateUtils
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.imdb.ImdbException
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * IMDb: the account whose watchlist and ratings the app reads (rated movies show as watched; the
 * watchlist is the Library's "IMDb List"), when it last synced, and syncing or unlinking it.
 */
@Composable
fun ImdbSettingsContent(
    viewModel: ImdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val profileName by viewModel.profileName.collectAsStateWithLifecycle()
    var showAccountDialog by remember { mutableStateOf(false) }
    var showSignIn by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val phoneLink by viewModel.phoneLink.collectAsStateWithLifecycle()

    // The phone's page runs only while this page is on screen.
    DisposableEffect(viewModel) {
        viewModel.startPhoneLink()
        onDispose { viewModel.stopPhoneLink() }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is ImdbSettingsMessage.Connected -> if (message.account.error == ImdbException.Reason.PRIVATE) {
                    context.getString(R.string.imdb_error_private)
                } else {
                    context.getString(R.string.imdb_connected_toast, message.account.ratedCount, message.account.watchlistCount)
                }
                is ImdbSettingsMessage.Failed -> context.getString(imdbErrorText(message.reason))
                ImdbSettingsMessage.Synced -> context.getString(R.string.imdb_synced_toast)
                ImdbSettingsMessage.Disconnected -> context.getString(R.string.imdb_unlinked_toast)
                ImdbSettingsMessage.SignedIn -> context.getString(R.string.imdb_signed_in_toast)
                ImdbSettingsMessage.SignedOut -> context.getString(R.string.imdb_signed_out_toast)
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.imdb_title),
            subtitle = stringResource(R.string.imdb_subtitle)
        )

        // RCTV: the options, and beside them the QR to link it from the phone (pasting the link
        // there instead of typing it with the remote; typing it here still works as before).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            SettingsGroupCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "imdb_account") {
                            SettingsActionRow(
                                title = stringResource(R.string.imdb_account_title),
                                // Linked for this profile only: every profile links its own.
                                subtitle = profileName?.let { stringResource(R.string.imdb_account_subtitle_profile, it) }
                                    ?: stringResource(R.string.imdb_account_subtitle),
                                value = account.nickname ?: account.userId ?: stringResource(R.string.imdb_not_linked),
                                onClick = { showAccountDialog = true },
                                modifier = Modifier
                                    .padding(top = NuvioTheme.spacing.xxs)
                                    .then(if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier)
                            )
                        }

                        // Signed in, what's watched here is marked watched on IMDb (this profile only).
                        item(key = "imdb_sign_in") {
                            if (account.isSignedIn && !account.signInExpired) {
                                SettingsActionRow(
                                    title = stringResource(R.string.imdb_sign_out_title),
                                    subtitle = stringResource(R.string.imdb_sign_out_subtitle),
                                    value = stringResource(R.string.imdb_signed_in_value),
                                    onClick = viewModel::signOut
                                )
                            } else {
                                SettingsActionRow(
                                    title = stringResource(R.string.imdb_sign_in_title),
                                    subtitle = stringResource(
                                        if (account.signInExpired) R.string.imdb_error_signed_out else R.string.imdb_sign_in_subtitle
                                    ),
                                    onClick = { showSignIn = true }
                                )
                            }
                        }

                        if (account.isConnected) {
                            item(key = "imdb_sync") {
                                val lastSync = account.lastSyncMs.takeIf { it > 0 }?.let { syncedAt ->
                                    stringResource(
                                        R.string.imdb_last_sync,
                                        DateUtils.getRelativeTimeSpanString(syncedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                                    )
                                } ?: stringResource(R.string.imdb_never_synced)
                                SettingsActionRow(
                                    title = stringResource(R.string.imdb_sync_title),
                                    subtitle = account.error?.let { stringResource(imdbErrorText(it)) }
                                        ?: stringResource(R.string.imdb_sync_counts, account.watchlistCount, account.ratedCount),
                                    value = if (account.isSyncing) stringResource(R.string.imdb_syncing) else lastSync,
                                    onClick = viewModel::syncNow,
                                    enabled = !account.isSyncing
                                )
                            }

                            item(key = "imdb_unlink") {
                                SettingsActionRow(
                                    title = stringResource(R.string.imdb_unlink_title),
                                    subtitle = stringResource(R.string.imdb_unlink_subtitle),
                                    onClick = viewModel::disconnect
                                )
                            }
                        }
                    }
                    SettingsVerticalScrollIndicators(state = listState)
                }
            }
            ImdbPhoneLinkPanel(
                link = phoneLink,
                modifier = Modifier.width(PHONE_PANEL_WIDTH)
            )
        }
    }

    if (showSignIn) {
        ImdbSignInDialog(
            onSignedIn = { cookies, profileKey ->
                showSignIn = false
                viewModel.signedIn(cookies, profileKey)
            },
            onDismiss = { showSignIn = false }
        )
    }

    if (showAccountDialog) {
        ImdbAccountDialog(
            currentValue = account.input.ifBlank { account.userId.orEmpty() },
            viewModel = viewModel,
            onDismiss = { showAccountDialog = false }
        )
    }
}

internal fun imdbErrorText(reason: ImdbException.Reason): Int = when (reason) {
    ImdbException.Reason.BAD_ID -> R.string.imdb_error_bad_id
    ImdbException.Reason.NOT_FOUND -> R.string.imdb_error_not_found
    ImdbException.Reason.PRIVATE -> R.string.imdb_error_private
    ImdbException.Reason.SIGNED_OUT -> R.string.imdb_error_signed_out
    ImdbException.Reason.FAILED -> R.string.imdb_error_failed
}

/** Types (or pastes) the IMDb profile id or link, and links it once IMDb knows it. */
@Composable
private fun ImdbAccountDialog(
    currentValue: String,
    viewModel: ImdbSettingsViewModel,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(10.dp)

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.imdb_dialog_title),
        subtitle = stringResource(R.string.imdb_dialog_subtitle),
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = shape),
                focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
            ),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(if (isInputFocused) NuvioTheme.colors.Primary else Color.Transparent),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.imdb_dialog_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { viewModel.connect(value, onDismiss) },
                enabled = !connecting,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(if (connecting) R.string.imdb_linking else R.string.action_save))
            }
        }
    }
}

private val PHONE_PANEL_WIDTH = 230.dp
private val PHONE_QR_SIZE = 160.dp

/**
 * RCTV: "Link from your phone": the QR the phone's camera scans. It opens the TV's little IMDb page,
 * where the IMDb link is pasted (copied in the IMDb app or site) and the TV links it at once.
 */
@Composable
private fun ImdbPhoneLinkPanel(
    link: ImdbPhoneLink?,
    modifier: Modifier = Modifier
) {
    val glass = isGlassSettingsStyle()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (glass) SettingsGlassRowFill else NuvioTheme.colors.BackgroundCard, shape)
            .border(1.dp, if (glass) SettingsGlassPanelBorder else NuvioTheme.colors.Border, shape)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.imdb_phone_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        val qr = link?.qr
        when {
            qr != null -> Image(
                bitmap = remember(qr) { qr.asImageBitmap() },
                contentDescription = stringResource(R.string.cd_qr_code),
                modifier = Modifier.size(PHONE_QR_SIZE),
                contentScale = ContentScale.Fit
            )
            else -> Box(
                modifier = Modifier
                    .size(PHONE_QR_SIZE)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                link?.error?.let { error ->
                    Text(
                        text = stringResource(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(NuvioTheme.spacing.sm)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.imdb_phone_hint),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
        link?.url?.let { url ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = url,
                fontSize = 11.sp,
                color = NuvioTheme.colors.TextTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}
