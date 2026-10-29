@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.util.languageCodeToName
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun AudioSelectionOverlay(
    visible: Boolean,
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    audioDelayMs: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    centerMixLevelDb: Int,
    isCenterMixAvailable: Boolean,
    persistAmplification: Boolean,
    onTrackSelected: (Int) -> Unit,
    /** RCTV: the language playing, the one being looked for, and choosing one (English, Español). */
    currentLanguage: com.nuvio.tv.core.smartscraper.AudioLanguage? = null,
    switchingLanguage: com.nuvio.tv.core.smartscraper.AudioLanguage? = null,
    onLanguageSelected: (com.nuvio.tv.core.smartscraper.AudioLanguage) -> Unit = {},
    onAudioDelayChange: (Int) -> Unit,
    onAmplificationChange: (Int) -> Unit,
    onCenterMixLevelChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** RCTV movie controls: the subtitles beside the audio (one screen for both); null: audio only. */
    subtitles: AudioScreenSubtitles? = null
) {
    val tracksFocusRequester = remember { FocusRequester() }
    val subtitlesFocusRequester = remember { FocusRequester() }
    val delayMinusFocusRequester = remember { FocusRequester() }
    val delayPlusFocusRequester = remember { FocusRequester() }
    val ampMinusFocusRequester = remember { FocusRequester() }
    val ampPlusFocusRequester = remember { FocusRequester() }
    val centerMinusFocusRequester = remember { FocusRequester() }
    val centerPlusFocusRequester = remember { FocusRequester() }
    val persistFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val currentDelayMs = audioDelayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    val canDecreaseDelay = currentDelayMs > AUDIO_DELAY_MIN_MS
    val canIncreaseDelay = currentDelayMs < AUDIO_DELAY_MAX_MS
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecreaseAmp = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncreaseAmp = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val currentCenterMixDb = centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    val canDecreaseCenterMix = isCenterMixAvailable && currentCenterMixDb > CENTER_MIX_LEVEL_MIN_DB
    val canIncreaseCenterMix = isCenterMixAvailable && currentCenterMixDb < CENTER_MIX_LEVEL_MAX_DB

    var lastFocusedAudioIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingControlFocusTarget by rememberSaveable {
        mutableStateOf<AudioControlFocusTarget?>(null)
    }

    LaunchedEffect(visible, tracks, selectedIndex) {
        if (!visible) return@LaunchedEffect

        // RCTV: the two languages (English, Español) are always there to focus.
        delay(120)
        runCatching { tracksFocusRequester.requestFocus() }
    }

    LaunchedEffect(visible, pendingControlFocusTarget, audioAmplificationDb, isAmplificationAvailable) {
        if (!visible) return@LaunchedEffect
        val target = pendingControlFocusTarget ?: return@LaunchedEffect
        val targetCanFocus = when (target) {
            AudioControlFocusTarget.DelayMinus -> canDecreaseDelay
            AudioControlFocusTarget.DelayPlus -> canIncreaseDelay
            AudioControlFocusTarget.AmpMinus -> canDecreaseAmp
            AudioControlFocusTarget.AmpPlus -> canIncreaseAmp
            AudioControlFocusTarget.CenterMinus -> canDecreaseCenterMix
            AudioControlFocusTarget.CenterPlus -> canIncreaseCenterMix
            AudioControlFocusTarget.Persist -> true
        }
        if (!targetCanFocus) return@LaunchedEffect
        val controlFocusRequester = when (target) {
            AudioControlFocusTarget.DelayMinus -> delayMinusFocusRequester
            AudioControlFocusTarget.DelayPlus -> delayPlusFocusRequester
            AudioControlFocusTarget.AmpMinus -> ampMinusFocusRequester
            AudioControlFocusTarget.AmpPlus -> ampPlusFocusRequester
            AudioControlFocusTarget.CenterMinus -> centerMinusFocusRequester
            AudioControlFocusTarget.CenterPlus -> centerPlusFocusRequester
            AudioControlFocusTarget.Persist -> persistFocusRequester
        }
        delay(80)
        controlFocusRequester.requestFocusAfterFrames(frames = 2)
        delay(200)
        runCatching { controlFocusRequester.requestFocus() }
        pendingControlFocusTarget = null
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp)
    ) {
        Column(
            modifier = Modifier
                .width(if (subtitles != null) 872.dp else 724.dp)
                .align(Alignment.BottomStart)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = stringResource(if (subtitles != null) R.string.player_audio_subtitles_title else R.string.audio_dialog_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
            ) {
                val controlsFocusRequester = when {
                    canDecreaseDelay -> delayMinusFocusRequester
                    canIncreaseDelay -> delayPlusFocusRequester
                    canDecreaseAmp -> ampMinusFocusRequester
                    canIncreaseAmp -> ampPlusFocusRequester
                    canDecreaseCenterMix -> centerMinusFocusRequester
                    canIncreaseCenterMix -> centerPlusFocusRequester
                    else -> persistFocusRequester
                }
                Column(modifier = Modifier.width(if (subtitles != null) 260.dp else 444.dp)) {
                    if (subtitles != null) {
                        Text(
                            text = stringResource(R.string.audio_dialog_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
                        )
                    }
                    // RCTV: English, then Español; only the audio changes.
                    AudioLanguagesContent(
                        currentLanguage = currentLanguage,
                        switchingLanguage = switchingLanguage,
                        initialFocusRequester = tracksFocusRequester,
                        rightFocusRequester = if (subtitles != null) subtitlesFocusRequester else controlsFocusRequester,
                        onLanguageSelected = onLanguageSelected
                    )
                }
                if (subtitles != null) {
                    Column(modifier = Modifier.width(300.dp)) {
                        AudioScreenSubtitlesColumn(
                            subtitles = subtitles,
                            firstFocusRequester = subtitlesFocusRequester,
                            leftFocusRequester = tracksFocusRequester,
                            rightFocusRequester = controlsFocusRequester
                        )
                    }
                }
                Column(modifier = Modifier.width(268.dp)) {
                    AudioControlsContent(
                        audioDelayMs = audioDelayMs,
                        audioAmplificationDb = audioAmplificationDb,
                        isAmplificationAvailable = isAmplificationAvailable,
                        centerMixLevelDb = centerMixLevelDb,
                        isCenterMixAvailable = isCenterMixAvailable,
                        persistAmplification = persistAmplification,
                        delayMinusFocusRequester = delayMinusFocusRequester,
                        delayPlusFocusRequester = delayPlusFocusRequester,
                        ampMinusFocusRequester = ampMinusFocusRequester,
                        ampPlusFocusRequester = ampPlusFocusRequester,
                        centerMinusFocusRequester = centerMinusFocusRequester,
                        centerPlusFocusRequester = centerPlusFocusRequester,
                        persistFocusRequester = persistFocusRequester,
                        leftFocusRequester = if (subtitles != null) subtitlesFocusRequester else tracksFocusRequester,
                        onAudioDelayChange = onAudioDelayChange,
                        onAmplificationChange = { nextDb, focusTarget ->
                            pendingControlFocusTarget = focusTarget
                            onAmplificationChange(nextDb)
                        },
                        onCenterMixLevelChange = onCenterMixLevelChange,
                        onPersistAmplificationChange = onPersistAmplificationChange
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioLanguagesContent(
    currentLanguage: com.nuvio.tv.core.smartscraper.AudioLanguage?,
    switchingLanguage: com.nuvio.tv.core.smartscraper.AudioLanguage?,
    initialFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onLanguageSelected: (com.nuvio.tv.core.smartscraper.AudioLanguage) -> Unit
) {
    val focused = currentLanguage ?: com.nuvio.tv.core.smartscraper.AudioLanguage.ENGLISH
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm)
            .fillMaxWidth()
    ) {
        com.nuvio.tv.core.smartscraper.AudioLanguage.entries.forEach { language ->
            AudioLanguageCard(
                name = language.nativeName,
                detail = if (language == switchingLanguage) stringResource(R.string.audio_language_searching_short) else null,
                isSelected = language == currentLanguage,
                rightFocusRequester = rightFocusRequester,
                focusRequester = if (language == focused) initialFocusRequester else null,
                onClick = { onLanguageSelected(language) }
            )
        }
    }
}

@Composable
private fun AudioLanguageCard(
    name: String,
    detail: String?,
    isSelected: Boolean,
    rightFocusRequester: FocusRequester,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { right = rightFocusRequester },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NuvioTheme.colors.Secondary else Color.Transparent,
            focusedContainerColor = if (isSelected) NuvioTheme.colors.Secondary else Color.Transparent
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) NuvioTheme.colors.OnSecondary else Color.White
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) NuvioTheme.colors.OnSecondary.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.72f)
                    )
                }
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioTheme.colors.OnSecondary
                )
            }
        }
    }
}

@Composable
private fun AudioTracksContent(
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    initialFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onTrackFocused: (Int) -> Unit,
    onTrackSelected: (Int) -> Unit
) {
    if (tracks.isEmpty()) {
        Text(
            text = stringResource(R.string.audio_lang_default),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.md)
        )
        return
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
        modifier = Modifier
            .heightIn(max = 620.dp)
            .fillMaxWidth()
    ) {
        items(items = tracks, key = { track -> track.index }) { track ->
            AudioTrackCard(
                track = track,
                isSelected = track.index == selectedIndex,
                onFocused = { onTrackFocused(track.index) },
                onClick = { onTrackSelected(track.index) },
                rightFocusRequester = rightFocusRequester,
                focusRequester = if (
                    track.index == selectedIndex || (selectedIndex < 0 && track == tracks.firstOrNull())
                ) {
                    initialFocusRequester
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun AudioTrackCard(
    track: TrackInfo,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    rightFocusRequester: FocusRequester,
    focusRequester: FocusRequester?
) {
    val metadata = listOfNotNull(
        track.codec,
        track.channelCount?.let { "$it ch" },
        track.sampleRate?.let { "${it / 1000} kHz" }
    ).joinToString(" | ")

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { right = rightFocusRequester }
            .onFocusChanged { if (it.isFocused) onFocused() },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NuvioTheme.colors.Secondary else Color.Transparent,
            focusedContainerColor = if (isSelected) NuvioTheme.colors.Secondary else Color.Transparent
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        val primaryTextColor = if (isSelected) NuvioTheme.colors.OnSecondary else Color.White
        val secondaryTextColor = if (isSelected) {
            NuvioTheme.colors.OnSecondary.copy(alpha = 0.82f)
        } else {
            Color.White.copy(alpha = 0.72f)
        }
        val metadataTextColor = if (isSelected) {
            NuvioTheme.colors.OnSecondary.copy(alpha = 0.72f)
        } else {
            NuvioTheme.colors.TextTertiary
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = primaryTextColor
                )
                if (!track.language.isNullOrBlank() && track.language != "und") {
                    Text(
                        text = languageCodeToName(track.language),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                }
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = metadataTextColor
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioTheme.colors.OnSecondary
                )
            }
        }
    }
}

@Composable
private fun AudioControlsContent(
    audioDelayMs: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    centerMixLevelDb: Int,
    isCenterMixAvailable: Boolean,
    persistAmplification: Boolean,
    delayMinusFocusRequester: FocusRequester,
    delayPlusFocusRequester: FocusRequester,
    ampMinusFocusRequester: FocusRequester,
    ampPlusFocusRequester: FocusRequester,
    centerMinusFocusRequester: FocusRequester,
    centerPlusFocusRequester: FocusRequester,
    persistFocusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    onAudioDelayChange: (Int) -> Unit,
    onAmplificationChange: (Int, AudioControlFocusTarget) -> Unit,
    onCenterMixLevelChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit
) {
    val currentDelayMs = audioDelayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    val canDecreaseDelay = currentDelayMs > AUDIO_DELAY_MIN_MS
    val canIncreaseDelay = currentDelayMs < AUDIO_DELAY_MAX_MS
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecreaseAmp = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncreaseAmp = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val currentCenterMixDb = centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    val canDecreaseCenterMix = isCenterMixAvailable && currentCenterMixDb > CENTER_MIX_LEVEL_MIN_DB
    val canIncreaseCenterMix = isCenterMixAvailable && currentCenterMixDb < CENTER_MIX_LEVEL_MAX_DB

    val firstDelayFocusRequester = if (canDecreaseDelay) {
        delayMinusFocusRequester
    } else {
        delayPlusFocusRequester
    }
    val firstAmpFocusRequester = when {
        canDecreaseAmp -> ampMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        else -> persistFocusRequester
    }
    val firstCenterFocusRequester = when {
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        else -> persistFocusRequester
    }
    val persistUpFocusRequester = when {
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        canDecreaseAmp -> ampMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseDelay -> delayMinusFocusRequester
        else -> delayPlusFocusRequester
    }
    val delayPlusLeftFocusRequester = if (canDecreaseDelay) {
        delayMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val ampPlusLeftFocusRequester = if (canDecreaseAmp) {
        ampMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val centerPlusLeftFocusRequester = if (canDecreaseCenterMix) {
        centerMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val persistLeftFocusRequester = when {
        canIncreaseCenterMix -> centerPlusFocusRequester
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseAmp -> ampMinusFocusRequester
        else -> leftFocusRequester
    }
    val centerHelperText = if (isCenterMixAvailable) {
        stringResource(R.string.audio_center_mix_help)
    } else {
        stringResource(R.string.audio_center_mix_unavailable)
    }

    val amplificationHelperText = when {
        !isAmplificationAvailable -> stringResource(R.string.audio_mix_unavailable)
        persistAmplification -> stringResource(
            R.string.audio_mix_range_saved,
            AUDIO_AMPLIFICATION_MIN_DB,
            AUDIO_AMPLIFICATION_MAX_DB
        )
        else -> stringResource(
            R.string.audio_mix_range,
            AUDIO_AMPLIFICATION_MIN_DB,
            AUDIO_AMPLIFICATION_MAX_DB
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AdjustmentSection(
            title = stringResource(R.string.audio_delay_label),
            valueText = formatAudioDelay(currentDelayMs),
            helperText = stringResource(
                R.string.audio_delay_range,
                AUDIO_DELAY_MIN_MS / 1000f,
                AUDIO_DELAY_MAX_MS / 1000f
            ),
            canDecrease = canDecreaseDelay,
            canIncrease = canIncreaseDelay,
            minusFocusRequester = delayMinusFocusRequester,
            plusFocusRequester = delayPlusFocusRequester,
            minusLeftFocusRequester = leftFocusRequester,
            plusLeftFocusRequester = delayPlusLeftFocusRequester,
            upFocusRequester = null,
            downFocusRequester = firstAmpFocusRequester,
            onDecrease = {
                val nextDelayMs = currentDelayMs - AUDIO_DELAY_STEP_MS
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs <= AUDIO_DELAY_MIN_MS && canIncreaseDelay) {
                    runCatching { delayPlusFocusRequester.requestFocus() }
                }
            },
            onIncrease = {
                val nextDelayMs = currentDelayMs + AUDIO_DELAY_STEP_MS
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs >= AUDIO_DELAY_MAX_MS && canDecreaseDelay) {
                    runCatching { delayMinusFocusRequester.requestFocus() }
                }
            }
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AdjustmentSection(
                title = stringResource(R.string.audio_mix_label),
                valueText = stringResource(R.string.audio_mix_value_db, currentDb),
                helperText = amplificationHelperText,
                canDecrease = canDecreaseAmp,
                canIncrease = canIncreaseAmp,
                minusFocusRequester = ampMinusFocusRequester,
                plusFocusRequester = ampPlusFocusRequester,
                minusLeftFocusRequester = leftFocusRequester,
                plusLeftFocusRequester = ampPlusLeftFocusRequester,
                upFocusRequester = firstDelayFocusRequester,
                downFocusRequester = firstCenterFocusRequester,
                onDecrease = {
                    val nextDb = currentDb - 1
                    val target = if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncreaseAmp) {
                        AudioControlFocusTarget.AmpPlus
                    } else {
                        AudioControlFocusTarget.AmpMinus
                    }
                    onAmplificationChange(nextDb, target)
                    if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncreaseAmp) {
                        runCatching { ampPlusFocusRequester.requestFocus() }
                    }
                },
                onIncrease = {
                    val nextDb = currentDb + 1
                    val target = if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecreaseAmp) {
                        AudioControlFocusTarget.AmpMinus
                    } else {
                        AudioControlFocusTarget.AmpPlus
                    }
                    onAmplificationChange(nextDb, target)
                    if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecreaseAmp) {
                        runCatching { ampMinusFocusRequester.requestFocus() }
                    }
                }
            )

            AdjustmentSection(
                title = stringResource(R.string.audio_center_mix_label),
                valueText = stringResource(R.string.audio_center_mix_value_db, currentCenterMixDb),
                helperText = centerHelperText,
                canDecrease = canDecreaseCenterMix,
                canIncrease = canIncreaseCenterMix,
                minusFocusRequester = centerMinusFocusRequester,
                plusFocusRequester = centerPlusFocusRequester,
                minusLeftFocusRequester = leftFocusRequester,
                plusLeftFocusRequester = centerPlusLeftFocusRequester,
                upFocusRequester = firstAmpFocusRequester,
                downFocusRequester = persistFocusRequester,
                onDecrease = {
                    val nextDb = currentCenterMixDb - 1
                    onCenterMixLevelChange(nextDb)
                    if (nextDb <= CENTER_MIX_LEVEL_MIN_DB && canIncreaseCenterMix) {
                        runCatching { centerPlusFocusRequester.requestFocus() }
                    }
                },
                onIncrease = {
                    val nextDb = currentCenterMixDb + 1
                    onCenterMixLevelChange(nextDb)
                    if (nextDb >= CENTER_MIX_LEVEL_MAX_DB && canDecreaseCenterMix) {
                        runCatching { centerMinusFocusRequester.requestFocus() }
                    }
                }
            )

            Card(
                onClick = { onPersistAmplificationChange(!persistAmplification) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(persistFocusRequester)
                    .focusProperties {
                        left = persistLeftFocusRequester
                        up = persistUpFocusRequester
                    },
                colors = CardDefaults.colors(
                    containerColor = if (persistAmplification) NuvioTheme.colors.Secondary else Color.Transparent,
                    focusedContainerColor = if (persistAmplification) NuvioTheme.colors.Secondary else Color.Transparent
                ),
                shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                border = CardDefaults.border(
                    border = Border(
                        border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    ),
                    focusedBorder = Border(
                        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    )
                ),
                scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
            ) {
                Text(
                    text = if (persistAmplification) {
                        stringResource(R.string.audio_mix_persist_on)
                    } else {
                        stringResource(R.string.audio_mix_persist_off)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (persistAmplification) NuvioTheme.colors.OnSecondary else Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun AdjustmentSection(
    title: String,
    valueText: String,
    helperText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    minusFocusRequester: FocusRequester,
    plusFocusRequester: FocusRequester,
    minusLeftFocusRequester: FocusRequester,
    plusLeftFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f)
        )

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StepCard(
                icon = Icons.Default.Remove,
                enabled = canDecrease,
                focusRequester = minusFocusRequester,
                leftFocusRequester = minusLeftFocusRequester,
                rightFocusRequester = if (canIncrease) plusFocusRequester else FocusRequester.Default,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                onClick = onDecrease
            )
            StepCard(
                icon = Icons.Default.Add,
                enabled = canIncrease,
                focusRequester = plusFocusRequester,
                leftFocusRequester = plusLeftFocusRequester,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                onClick = onIncrease
            )
        }

        Text(
            text = helperText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.66f)
        )
    }
}

@Composable
private fun StepCard(
    icon: ImageVector,
    enabled: Boolean,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester = FocusRequester.Default,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = {
            if (enabled) {
                onClick()
            }
        },
        modifier = Modifier
            .width(NuvioTheme.spacing.huge)
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = enabled
                left = leftFocusRequester
                right = rightFocusRequester
                upFocusRequester?.let { up = it }
                downFocusRequester?.let { down = it }
            },
        colors = CardDefaults.colors(
            containerColor = if (enabled) Color.Transparent else Color.White.copy(alpha = 0.06f),
            focusedContainerColor = if (enabled) Color.Transparent else Color.White.copy(alpha = 0.06f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, if (enabled) Color.White.copy(alpha = 0.18f) else Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(
                    width = NuvioTheme.spacing.xxs,
                    alpha = if (enabled) 1f else 0f
                ),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
            )
        }
    }
}

private enum class AudioControlFocusTarget {
    DelayMinus,
    DelayPlus,
    AmpMinus,
    AmpPlus,
    CenterMinus,
    CenterPlus,
    Persist
}

private fun formatAudioDelay(delayMs: Int): String {
    return if (delayMs == 0) {
        String.format(Locale.US, "%.3fs", 0f)
    } else {
        String.format(Locale.US, "%+.3fs", delayMs / 1000f)
    }
}

/**
 * RCTV movie controls: the subtitles on the audio's screen (one screen for both): Off, the video's
 * own subtitles, the addons' languages, and the full subtitles screen for the rest (style, timing,
 * every file).
 */
internal data class AudioScreenSubtitles(
    val internalTracks: List<TrackInfo>,
    val selectedInternalIndex: Int,
    val addonSubtitles: List<com.nuvio.tv.domain.model.Subtitle>,
    val selectedAddonSubtitle: com.nuvio.tv.domain.model.Subtitle?,
    val onInternalSelected: (Int) -> Unit,
    val onAddonSelected: (com.nuvio.tv.domain.model.Subtitle) -> Unit,
    val onDisable: () -> Unit,
    val onMoreOptions: () -> Unit
)

private data class SubtitleChoice(
    val key: String,
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun AudioScreenSubtitlesColumn(
    subtitles: AudioScreenSubtitles,
    firstFocusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester
) {
    val offLabel = stringResource(R.string.subtitle_off)
    val moreLabel = stringResource(R.string.player_more_subtitle_options)
    val choices = remember(subtitles, offLabel, moreLabel) {
        val noneSelected = subtitles.selectedInternalIndex < 0 && subtitles.selectedAddonSubtitle == null
        buildList {
            add(SubtitleChoice("off", offLabel, null, noneSelected, subtitles.onDisable))
            subtitles.internalTracks.forEach { track ->
                val language = track.language?.takeIf { it.isNotBlank() }?.let(::languageCodeToName)?.takeIf { it.isNotBlank() }
                val label = language ?: track.name.ifBlank { offLabel }
                val detail = track.name.takeIf { it.isNotBlank() && !it.equals(label, ignoreCase = true) }
                add(
                    SubtitleChoice(
                        key = "internal:${track.index}",
                        label = label,
                        detail = detail,
                        selected = subtitles.selectedAddonSubtitle == null && track.index == subtitles.selectedInternalIndex,
                        onClick = { subtitles.onInternalSelected(track.index) }
                    )
                )
            }
            // One per language from the addons (the full screen has every file).
            subtitles.addonSubtitles
                .distinctBy { it.lang.trim().lowercase(Locale.ROOT) }
                .take(12)
                .forEach { subtitle ->
                    add(
                        SubtitleChoice(
                            key = "addon:${subtitle.lang.lowercase(Locale.ROOT)}",
                            label = subtitle.getDisplayLanguage(),
                            detail = null,
                            selected = subtitles.selectedAddonSubtitle?.lang.equals(subtitle.lang, ignoreCase = true),
                            onClick = { subtitles.onAddonSelected(subtitle) }
                        )
                    )
                }
            add(SubtitleChoice("more", moreLabel, null, false, subtitles.onMoreOptions))
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.subtitle_dialog_title),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
        )
        LazyColumn(
            modifier = Modifier
                .padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm)
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(choices, key = { it.key }) { choice ->
                SubtitleChoiceCard(
                    choice = choice,
                    focusRequester = if (choice.key == choices.first().key) firstFocusRequester else null,
                    leftFocusRequester = leftFocusRequester,
                    rightFocusRequester = rightFocusRequester
                )
            }
        }
    }
}

@Composable
private fun SubtitleChoiceCard(
    choice: SubtitleChoice,
    focusRequester: FocusRequester?,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester
) {
    Card(
        onClick = choice.onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                left = leftFocusRequester
                right = rightFocusRequester
            },
        colors = CardDefaults.colors(
            containerColor = if (choice.selected) NuvioTheme.colors.Secondary else Color.Transparent,
            focusedContainerColor = if (choice.selected) NuvioTheme.colors.Secondary else Color.Transparent
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = choice.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (choice.selected) NuvioTheme.colors.OnSecondary else Color.White
                )
                if (choice.detail != null) {
                    Text(
                        text = choice.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (choice.selected) NuvioTheme.colors.OnSecondary.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.72f)
                    )
                }
            }
            if (choice.selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioTheme.colors.OnSecondary
                )
            }
        }
    }
}
