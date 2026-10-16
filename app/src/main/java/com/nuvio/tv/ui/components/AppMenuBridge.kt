package com.nuvio.tv.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets a page that isn't one of the menu's own sections open the app's menu: a streaming service's
 * page asks for it on Back (once it's at the top), so Back goes page top, then menu, then Home.
 */
class AppMenuBridge {
    /** True while a page that opens the menu on Back is showing. */
    var pageOpensMenu by mutableStateOf(false)

    /** Opens the menu (set by the menu's host). */
    var open: () -> Unit = {}
}

/** The menu's bridge; null where the app's menu can't be opened from a page (the classic sidebar). */
val LocalAppMenuBridge = staticCompositionLocalOf<AppMenuBridge?> { null }
