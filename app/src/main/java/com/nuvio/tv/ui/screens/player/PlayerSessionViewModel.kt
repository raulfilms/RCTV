package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import com.nuvio.tv.core.playback.PlaybackSession

private const val PLAYER_VIEW_MODEL_KEY = "rctv_playback_session_player"

/**
 * RCTV (owner): the player screen's ViewModel, kept in the playback session (see [PlaybackSession])
 * instead of the screen's own back-stack entry, so Back can leave it playing while the app is
 * browsed. Made with the activity's Hilt factory and the screen's arguments, exactly as before.
 */
@Composable
internal fun sessionPlayerViewModel(entry: NavBackStackEntry): PlayerViewModel {
    val context = LocalContext.current
    val store = remember(entry.id) { PlaybackSession.bind(entry) }
    val owner = remember(store) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }
    val activity = remember(context) { context.findComponentActivity() }
    val viewModel: PlayerViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = PLAYER_VIEW_MODEL_KEY,
        factory = activity?.defaultViewModelProviderFactory,
        extras = entry.defaultViewModelCreationExtras
    )
    DisposableEffect(viewModel, store) {
        PlaybackSession.attach(
            store = store,
            stop = { viewModel.stopAndRelease() },
            player = { viewModel.exoPlayer }
        )
        onDispose { }
    }
    return viewModel
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
