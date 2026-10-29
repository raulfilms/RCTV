package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.smartscraper.AudioLanguage
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.util.languageCodeToName
import java.util.Locale
import kotlinx.coroutines.delay

private val PageHeaderColor = Color(0xFF232323)
private val RowLineColor = Color(0xFF2A2A2A)
private val RowTextColor = Color(0xFFC9C9C9)

/** One choice on the page: its name, a word under it ("Searching…"), chosen, what choosing does. */
private data class PageChoice(
    val key: String,
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val onClick: () -> Unit
)

/**
 * RCTV: the player's Audio & Subtitles page, as Disney+'s (the title controls' first button): on
 * black, two columns under their grey bars, Audio (English, Español: only the audio changes) and
 * Subtitles (Off, the video's own, one per addon language); the one chosen in white with a ✓, the
 * focused one white with black words; ✕ at the top right closes it, as Back does. The audio's
 * timing and boost and the subtitles' style, timing and other files are in the controls' Settings.
 */
@Composable
internal fun AudioSubtitlesPage(
    visible: Boolean,
    currentLanguage: AudioLanguage?,
    switchingLanguage: AudioLanguage?,
    onLanguageSelected: (AudioLanguage) -> Unit,
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    onInternalSubtitleSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (Subtitle) -> Unit,
    onDisableSubtitles: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(160)),
        modifier = modifier
    ) {
        val closeFocusRequester = remember { FocusRequester() }
        val audioFocusRequester = remember { FocusRequester() }
        val searchingLabel = stringResource(R.string.audio_language_searching_short)
        val offLabel = stringResource(R.string.subtitle_off)

        val audioChoices = AudioLanguage.entries.map { language ->
            PageChoice(
                key = "audio:${language.code}",
                label = language.nativeName,
                detail = if (language == switchingLanguage) searchingLabel else null,
                selected = language == currentLanguage,
                onClick = { onLanguageSelected(language) }
            )
        }
        val subtitleChoices = remember(internalTracks, selectedInternalIndex, addonSubtitles, selectedAddonSubtitle, offLabel) {
            subtitleChoices(
                internalTracks, selectedInternalIndex, addonSubtitles, selectedAddonSubtitle, offLabel,
                onInternalSubtitleSelected, onAddonSubtitleSelected, onDisableSubtitles
            )
        }
        // The audio playing has the focus when the page opens (English when not known).
        val focusedAudioKey = (audioChoices.firstOrNull { it.selected } ?: audioChoices.first()).key

        LaunchedEffect(Unit) {
            delay(120)
            runCatching { audioFocusRequester.requestFocus() }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 18.dp)
                    .size(34.dp)
                    .focusRequester(closeFocusRequester),
                colors = IconButtonDefaults.colors(
                    containerColor = Color(0xFF5B5B5B),
                    focusedContainerColor = Color.White,
                    contentColor = Color(0xFF111111),
                    focusedContentColor = Color.Black
                ),
                shape = IconButtonDefaults.shape(shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    modifier = Modifier.size(20.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 58.dp, top = 52.dp, end = 58.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(84.dp)
            ) {
                PageColumn(
                    title = stringResource(R.string.audio_dialog_title),
                    choices = audioChoices,
                    focusKey = focusedAudioKey,
                    focusRequester = audioFocusRequester,
                    upFocusRequester = closeFocusRequester
                )
                PageColumn(
                    title = stringResource(R.string.subtitle_dialog_title),
                    choices = subtitleChoices,
                    focusKey = null,
                    focusRequester = null,
                    upFocusRequester = closeFocusRequester
                )
            }
        }
    }
}

/** Off, the video's own subtitles, then one per language from the addons (the full screen has every file). */
private fun subtitleChoices(
    internalTracks: List<TrackInfo>,
    selectedInternalIndex: Int,
    addonSubtitles: List<Subtitle>,
    selectedAddonSubtitle: Subtitle?,
    offLabel: String,
    onInternalSelected: (Int) -> Unit,
    onAddonSelected: (Subtitle) -> Unit,
    onDisable: () -> Unit
): List<PageChoice> = buildList {
    val noneSelected = selectedInternalIndex < 0 && selectedAddonSubtitle == null
    add(PageChoice("off", offLabel, null, noneSelected, onDisable))
    internalTracks.forEach { track ->
        val language = track.language?.takeIf { it.isNotBlank() }?.let(::languageCodeToName)?.takeIf { it.isNotBlank() }
        val label = language ?: track.name.ifBlank { offLabel }
        val detail = track.name.takeIf { it.isNotBlank() && !it.equals(label, ignoreCase = true) }
        add(
            PageChoice(
                key = "internal:${track.index}",
                label = label,
                detail = detail,
                selected = selectedAddonSubtitle == null && track.index == selectedInternalIndex,
                onClick = { onInternalSelected(track.index) }
            )
        )
    }
    addonSubtitles
        .distinctBy { it.lang.trim().lowercase(Locale.ROOT) }
        .take(16)
        .forEach { subtitle ->
            add(
                PageChoice(
                    key = "addon:${subtitle.lang.trim().lowercase(Locale.ROOT)}",
                    label = subtitle.getDisplayLanguage(),
                    detail = null,
                    selected = selectedAddonSubtitle?.lang?.trim().equals(subtitle.lang.trim(), ignoreCase = true),
                    onClick = { onAddonSelected(subtitle) }
                )
            )
        }
}

@Composable
private fun PageColumn(
    title: String,
    choices: List<PageChoice>,
    focusKey: String?,
    focusRequester: FocusRequester?,
    upFocusRequester: FocusRequester
) {
    Column(modifier = Modifier.width(334.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(PageHeaderColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFE6E6E6),
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(choices, key = { _, choice -> choice.key }) { index, choice ->
                PageChoiceRow(
                    choice = choice,
                    focusRequester = if (focusRequester != null && choice.key == focusKey) focusRequester else null,
                    upFocusRequester = if (index == 0) upFocusRequester else null
                )
            }
        }
    }
}

@Composable
private fun PageChoiceRow(
    choice: PageChoice,
    focusRequester: FocusRequester?,
    upFocusRequester: FocusRequester?
) {
    Column {
        Card(
            onClick = choice.onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (choice.detail != null) 56.dp else 46.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier),
            colors = CardDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White,
                contentColor = if (choice.selected) Color.White else RowTextColor,
                focusedContentColor = Color.Black
            ),
            shape = CardDefaults.shape(shape = RoundedCornerShape(5.dp)),
            scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 6.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) {
                    if (choice.selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = choice.label,
                        fontSize = 16.sp,
                        fontWeight = if (choice.selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (choice.detail != null) {
                        Text(
                            text = choice.detail,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(RowLineColor)
        )
    }
}
