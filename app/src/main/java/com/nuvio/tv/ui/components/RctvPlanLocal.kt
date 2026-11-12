package com.nuvio.tv.ui.components

import androidx.compose.runtime.compositionLocalOf
import com.nuvio.tv.core.rctvcontrol.RctvPlan

/**
 * RCTV Control: the account's plan right now. Light shows only movies and series (no live TV,
 * channels or sports anywhere); Full shows everything. Full where nothing provides it.
 */
val LocalRctvPlan = compositionLocalOf { RctvPlan.FULL }
