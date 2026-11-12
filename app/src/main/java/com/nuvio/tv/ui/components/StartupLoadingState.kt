package com.nuvio.tv.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class StartupLoadingState {
    var complete by mutableStateOf(false)
    /** RCTV, "Creating Your Experience": how full its bar is (0 to 1). */
    var progress by mutableStateOf(0f)
    /** RCTV: what loads behind "Creating Your Experience" is in (or it waited long enough). */
    var experienceReady by mutableStateOf(false)
}

val LocalStartupLoadingState = compositionLocalOf<StartupLoadingState?> { null }
/**
 * RCTV: "Creating Your Experience" covers the screen while Home loads behind it: Home's trailers
 * wait (no sound behind it) and the remote does nothing until it goes.
 */
val LocalStartupCovering = compositionLocalOf { false }
val LocalStartupSplashEnabled = compositionLocalOf { false }
