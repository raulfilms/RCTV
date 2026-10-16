package com.nuvio.tv.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets a page that isn't one of the menu's own sections open the app's menu: a streaming service's
 * page or a See All list asks for it on Back (once at the top), so Back goes top, then menu, then out.
 */
class AppMenuBridge {
    /** Pages showing right now that open the menu on Back (a service's page and its See All list...). */
    private var pages by mutableIntStateOf(0)

    /** True while a page that opens the menu on Back is showing. */
    val pageOpensMenu: Boolean get() = pages > 0

    fun registerPage() {
        pages++
    }

    fun unregisterPage() {
        pages = (pages - 1).coerceAtLeast(0)
    }

    /** Opens the menu (set by the menu's host). */
    var open: () -> Unit = {}

    /** Closes the menu (set by the menu's host). */
    var close: () -> Unit = {}

    /** Whether the menu is open (set by the menu's host). */
    var isMenuOpen: () -> Boolean = { false }
}

/** The menu's bridge; null where the app's menu can't be opened from a page (the classic sidebar). */
val LocalAppMenuBridge = staticCompositionLocalOf<AppMenuBridge?> { null }
