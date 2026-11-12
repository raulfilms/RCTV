package com.nuvio.tv.ui.components

import androidx.compose.runtime.compositionLocalOf
import com.nuvio.tv.core.rctvcontrol.RctvBanner
import com.nuvio.tv.core.rctvcontrol.RctvPlan

/**
 * RCTV Control: the account's plan right now. Light shows only movies and series (no live TV,
 * channels or sports anywhere); Full shows everything. Full where nothing provides it.
 */
val LocalRctvPlan = compositionLocalOf { RctvPlan.FULL }

/**
 * RCTV: true while a Kids profile is watching (movies and shows up to 13, family and kids channels,
 * no Sports).
 */
val LocalKidsProfile = compositionLocalOf { false }

/**
 * RCTV Control: the website's Home banners this profile sees right now (plan, Kids, dates), in
 * Home's order. None where nothing provides them.
 */
val LocalRctvHomeBanners = compositionLocalOf { emptyList<RctvBanner>() }
