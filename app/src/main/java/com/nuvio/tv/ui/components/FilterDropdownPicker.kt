package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import kotlinx.coroutines.delay

/** One entry in a [FilterDropdownPicker]'s popup list. */
data class FilterDropdownOption(
    val label: String,
    val value: String
)

/**
 * A TV-friendly labeled dropdown picker: a focusable pill showing the current selection, and a
 * popup list of [options] to choose from. Used for content filters (genre, year, ...) on browse
 * grids. Shares its visual language and D-pad focus-restore behavior with the picker used on the
 * Library screen.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilterDropdownPicker(
    modifier: Modifier = Modifier,
    upFocusRequester: FocusRequester? = null,
    title: String,
    value: String,
    selectedValue: String?,
    expanded: Boolean,
    options: List<FilterDropdownOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (FilterDropdownOption) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // Seed focused option with the current selection so reopen highlights the right row
    // even before focus lands.
    var focusedOptionValue by remember(expanded) {
        mutableStateOf(if (expanded) selectedValue else null)
    }
    val selectedItemFocusRequester = remember { FocusRequester() }
    val selectedBringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(expanded, selectedValue) {
        if (!expanded || selectedValue == null) return@LaunchedEffect
        var focused = selectedItemFocusRequester.requestFocusAfterFrames(frames = 3)
        var attempt = 0
        while (!focused && attempt < 6) {
            delay(32)
            focused = runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)
            attempt++
        }
        if (!focused) return@LaunchedEffect
        runCatching { selectedBringIntoViewRequester.bringIntoView() }
        delay(48)
        if (runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)) {
            runCatching { selectedBringIntoViewRequester.bringIntoView() }
        }
    }

    Box(modifier = modifier) {
        Card(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (upFocusRequester != null) {
                        Modifier.focusProperties { up = upFocusRequester }
                    } else {
                        Modifier
                    }
                )
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                border = androidx.tv.material3.Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.0f,
                pressedScale = 1.0f
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) stringResource(R.string.cd_collapse, title) else stringResource(R.string.cd_expand, title),
                        tint = if (isFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                focusedOptionValue = null
                onExpandedChange(false)
            },
            modifier = Modifier
                .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                .heightIn(max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NuvioTheme.colors.BackgroundCard,
            tonalElevation = NuvioTheme.spacing.none,
            shadowElevation = NuvioTheme.spacing.sm,
            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
        ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                val isOptionFocused = option.value == focusedOptionValue
                val itemTextColor = NuvioTheme.colors.TextPrimary
                val itemBackgroundColor = when {
                    isOptionFocused -> NuvioTheme.colors.Secondary
                    isSelected -> NuvioTheme.colors.FocusBackground
                    else -> Color.Transparent
                }

                DropdownMenuItem(
                    modifier = Modifier
                        .then(
                            if (isSelected) {
                                Modifier
                                    .focusRequester(selectedItemFocusRequester)
                                    .bringIntoViewRequester(selectedBringIntoViewRequester)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = NuvioTheme.spacing.xxs)
                        .background(
                            color = itemBackgroundColor,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .onFocusChanged { state ->
                            val hasFocus = state.isFocused || state.hasFocus
                            focusedOptionValue = when {
                                hasFocus -> option.value
                                focusedOptionValue == option.value -> null
                                else -> focusedOptionValue
                            }
                        },
                    text = {
                        Text(
                            text = option.label,
                            color = itemTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onSelect(option) },
                    colors = MenuDefaults.itemColors(
                        textColor = itemTextColor,
                        disabledTextColor = NuvioTheme.colors.TextDisabled
                    )
                )
            }
        }
    }
}

/**
 * A single compact filter icon (top-right corner style) that opens one combined dropdown menu
 * with a Genre section and a Year section together, instead of two separate pickers. Selecting a
 * row in either section applies immediately and keeps the menu open, so both facets can be picked
 * in one visit; the menu only closes on dismiss (back/select elsewhere) or "Clear filters".
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CombinedFilterMenuButton(
    modifier: Modifier = Modifier,
    upFocusRequester: FocusRequester? = null,
    contentDescription: String,
    typeLabel: String = "",
    genreLabel: String,
    yearLabel: String,
    allLabel: String,
    clearLabel: String,
    typeOptions: List<FilterDropdownOption> = emptyList(),
    genreOptions: List<FilterDropdownOption>,
    yearOptions: List<FilterDropdownOption>,
    selectedTypeValue: String = "__all__",
    selectedGenreValue: String,
    selectedYearValue: String,
    hasActiveFilter: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectType: (FilterDropdownOption) -> Unit = {},
    onSelectGenre: (FilterDropdownOption) -> Unit,
    onSelectYear: (FilterDropdownOption) -> Unit,
    onClearAll: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box {
            Card(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier
                    .size(48.dp)
                    .then(
                        if (upFocusRequester != null) {
                            Modifier.focusProperties { up = upFocusRequester }
                        } else {
                            Modifier
                        }
                    )
                    .onFocusChanged { isFocused = it.isFocused },
                shape = CardDefaults.shape(shape = CircleShape),
                colors = CardDefaults.colors(
                    containerColor = if (hasActiveFilter) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                border = CardDefaults.border(
                    border = androidx.tv.material3.Border(
                        border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                        shape = CircleShape
                    ),
                    focusedBorder = androidx.tv.material3.Border(
                        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                        shape = CircleShape
                    )
                ),
                scale = CardDefaults.scale(
                    focusedScale = 1.0f,
                    pressedScale = 1.0f
                )
            ) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = contentDescription,
                        tint = if (isFocused || hasActiveFilter) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextSecondary
                    )
                }
            }
            if (hasActiveFilter) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                        .background(color = NuvioTheme.colors.Secondary, shape = CircleShape)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier
                .widthIn(min = 240.dp, max = 280.dp)
                .heightIn(max = 420.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NuvioTheme.colors.BackgroundCard,
            tonalElevation = NuvioTheme.spacing.none,
            shadowElevation = NuvioTheme.spacing.sm,
            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
        ) {
            if (typeOptions.isNotEmpty()) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.xxs)
                )
                val typeAllOption = FilterDropdownOption(allLabel, "__all__")
                (listOf(typeAllOption) + typeOptions).forEach { option ->
                    FilterMenuOptionRow(
                        option = option,
                        isSelected = option.value == selectedTypeValue,
                        onClick = { onSelectType(option) }
                    )
                }
            }

            if (genreOptions.isNotEmpty()) {
                if (typeOptions.isNotEmpty()) {
                    Divider(color = NuvioTheme.colors.Border, thickness = NuvioTheme.spacing.hairline)
                }
                Text(
                    text = genreLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.xxs)
                )
                val genreAllOption = FilterDropdownOption(allLabel, "__all__")
                (listOf(genreAllOption) + genreOptions).forEach { option ->
                    FilterMenuOptionRow(
                        option = option,
                        isSelected = option.value == selectedGenreValue,
                        onClick = { onSelectGenre(option) }
                    )
                }
            }

            if (yearOptions.isNotEmpty()) {
                if (typeOptions.isNotEmpty() || genreOptions.isNotEmpty()) {
                    Divider(color = NuvioTheme.colors.Border, thickness = NuvioTheme.spacing.hairline)
                }
                Text(
                    text = yearLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.xxs)
                )
                val yearAllOption = FilterDropdownOption(allLabel, "__all__")
                (listOf(yearAllOption) + yearOptions).forEach { option ->
                    FilterMenuOptionRow(
                        option = option,
                        isSelected = option.value == selectedYearValue,
                        onClick = { onSelectYear(option) }
                    )
                }
            }

            if (hasActiveFilter) {
                Divider(color = NuvioTheme.colors.Border, thickness = NuvioTheme.spacing.hairline)
                DropdownMenuItem(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = NuvioTheme.spacing.xxs),
                    text = {
                        Text(
                            text = clearLabel,
                            color = NuvioTheme.colors.Secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        onClearAll()
                        onExpandedChange(false)
                    },
                    colors = MenuDefaults.itemColors(textColor = NuvioTheme.colors.Secondary)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterMenuOptionRow(
    option: FilterDropdownOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val itemTextColor = NuvioTheme.colors.TextPrimary
    val itemBackgroundColor = when {
        isFocused -> NuvioTheme.colors.Secondary
        isSelected -> NuvioTheme.colors.FocusBackground
        else -> Color.Transparent
    }

    DropdownMenuItem(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = NuvioTheme.spacing.xxs)
            .background(color = itemBackgroundColor, shape = RoundedCornerShape(10.dp))
            .onFocusChanged { state -> isFocused = state.isFocused || state.hasFocus },
        text = {
            Text(
                text = option.label,
                color = itemTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = itemTextColor,
            disabledTextColor = NuvioTheme.colors.TextDisabled
        )
    )
}
