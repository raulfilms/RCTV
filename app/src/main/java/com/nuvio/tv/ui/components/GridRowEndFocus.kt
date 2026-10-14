package com.nuvio.tv.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * For a title in a See All grid: Right on the last title of a row (nothing more to its right) goes
 * up to the page's filter ([target]), so the filter can be reached from anywhere in the grid, even
 * far down the list. Elsewhere Right works as usual. Does nothing when [target] is null (no filter).
 */
fun Modifier.rightAtRowEndFocuses(target: FocusRequester?, isRowEnd: () -> Boolean): Modifier =
    if (target == null) this
    else onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && isRowEnd()) {
            runCatching { target.requestFocus() }.getOrDefault(false)
        } else {
            false
        }
    }

/**
 * True when grid item [index] is the last one of its row: the next title starts a new row, or
 * [index] is the last of the [itemCount] titles. Works for fixed and adaptive columns alike.
 */
fun LazyGridState.isRowEnd(index: Int, itemCount: Int): Boolean {
    if (index >= itemCount - 1) return true
    val visible = layoutInfo.visibleItemsInfo
    val here = visible.firstOrNull { it.index == index } ?: return false
    // A row is laid out together, so a next title that isn't on screen is on another row.
    val next = visible.firstOrNull { it.index == index + 1 } ?: return true
    return next.row != here.row
}
