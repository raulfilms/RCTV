package com.nuvio.tv.ui.screens.sports

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RCTV (owner): "Hide scores", for whoever doesn't want to know how a game went before watching it.
 * Chosen on the Sports page's banner; kept on the TV. While on, no game card, banner or game or
 * team page shows a score (nor who won).
 */
object SportsScoreVisibility {
    private const val PREFS = "rctv_sports"
    private const val KEY_HIDDEN = "hide_scores"

    private val _hidden = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = _hidden.asStateFlow()

    @Volatile
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _hidden.value = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_HIDDEN, false)
            loaded = true
        }
    }

    fun toggle(context: Context) {
        load(context)
        val hide = !_hidden.value
        _hidden.value = hide
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDDEN, hide)
            .apply()
    }
}

/** Whether the scores are hidden now (follows the switch as it changes). */
@Composable
fun rememberScoresHidden(): Boolean {
    val context = LocalContext.current
    remember(context) { SportsScoreVisibility.load(context) }
    val hidden by SportsScoreVisibility.hidden.collectAsState()
    return hidden
}
