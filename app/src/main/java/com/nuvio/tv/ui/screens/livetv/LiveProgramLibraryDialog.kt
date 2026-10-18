package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Select held on what's on now in the Guide: add the episode or the whole series to the library (a
 * movie: the movie). It asks right away; what TMDB found shows as soon as it's in. A game, or
 * anything TMDB doesn't have, goes to the Library's Events on its own (this shows meanwhile); when
 * TMDB can't be reached, it can be added there anyway.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveProgramLibraryDialog(
    prompt: LiveLibraryPrompt,
    onChoice: (LiveLibraryChoice) -> Unit,
    onDismiss: () -> Unit
) {
    val firstButton = remember { FocusRequester() }
    val notFound = !prompt.isMatching && prompt.match == null
    // A game: nothing to choose while ESPN and TMDB are asked (it goes to the Events on its own).
    val waitingForGame = prompt.looksLikeGame && prompt.isMatching
    val match = prompt.match
    val episode = match?.episode
    // A series whose airing episode TMDB can't tell: only the series can be added.
    val canAddEpisode = match == null || episode != null
    // The buttons change once TMDB answers (a movie, not found...): focus the first one again.
    LaunchedEffect(prompt.isMovie, notFound, canAddEpisode, waitingForGame) {
        runCatching { firstButton.requestFocus() }
    }

    val details = when {
        prompt.isMatching -> stringResource(R.string.livetv_library_looking_up)
        prompt.lookupFailed -> stringResource(R.string.livetv_library_lookup_failed)
        match == null -> stringResource(R.string.livetv_library_adding_event)
        match.isMovie -> listOfNotNull(match.name, match.year?.let { "($it)" }).joinToString(" ")
        episode != null -> stringResource(R.string.livetv_library_episode_title, match.name, episode.season, episode.episode) +
            (episode.name?.let { " · $it" } ?: "")
        else -> stringResource(R.string.livetv_library_episode_unknown, match.name)
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = cleanProgramTitle(prompt.program.title),
        subtitle = stringResource(R.string.livetv_library_dialog_subtitle)
    ) {
        Text(
            text = details,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
        when {
            // Not on TMDB (or it couldn't be asked): the program, in the Library's Events.
            notFound -> Button(
                onClick = { onChoice(LiveLibraryChoice.EVENT) },
                enabled = !prompt.isAdding,
                modifier = Modifier.fillMaxWidth().focusRequester(firstButton),
                colors = colors
            ) { Text(stringResource(R.string.livetv_library_add_event)) }

            waitingForGame -> Unit

            prompt.isMovie -> Button(
                onClick = { onChoice(LiveLibraryChoice.MOVIE) },
                enabled = !prompt.isAdding,
                modifier = Modifier.fillMaxWidth().focusRequester(firstButton),
                colors = colors
            ) { Text(stringResource(R.string.livetv_library_add_movie)) }

            else -> {
                if (canAddEpisode) {
                    Button(
                        onClick = { onChoice(LiveLibraryChoice.EPISODE) },
                        enabled = !prompt.isAdding,
                        modifier = Modifier.fillMaxWidth().focusRequester(firstButton),
                        colors = colors
                    ) { Text(stringResource(R.string.livetv_library_add_episode)) }
                }
                Button(
                    onClick = { onChoice(LiveLibraryChoice.SERIES) },
                    enabled = !prompt.isAdding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (canAddEpisode) Modifier else Modifier.focusRequester(firstButton)),
                    colors = colors
                ) { Text(stringResource(R.string.livetv_library_add_series)) }
            }
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (waitingForGame) Modifier.focusRequester(firstButton) else Modifier),
            colors = colors
        ) { Text(stringResource(R.string.action_cancel)) }
    }
}
