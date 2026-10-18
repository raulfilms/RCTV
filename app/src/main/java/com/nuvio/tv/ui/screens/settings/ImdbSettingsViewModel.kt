package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.imdb.ImdbAccount
import com.nuvio.tv.data.imdb.ImdbSyncService
import com.nuvio.tv.data.remote.imdb.ImdbException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What happened when the user linked their IMDb account or synced it. */
sealed interface ImdbSettingsMessage {
    data class Connected(val account: ImdbAccount) : ImdbSettingsMessage
    data class Failed(val reason: ImdbException.Reason) : ImdbSettingsMessage
    data object Synced : ImdbSettingsMessage
    data object Disconnected : ImdbSettingsMessage
}

@HiltViewModel
class ImdbSettingsViewModel @Inject constructor(
    private val imdbSync: ImdbSyncService
) : ViewModel() {

    val account: StateFlow<ImdbAccount> = imdbSync.account

    private val _connecting = MutableStateFlow(false)
    /** The account the user typed is being checked. */
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _messages = MutableSharedFlow<ImdbSettingsMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<ImdbSettingsMessage> = _messages.asSharedFlow()

    /** Links the account typed ("ur12345678", "p.abc…" or the profile's link); [onDone] when it's linked. */
    fun connect(input: String, onDone: () -> Unit) {
        if (_connecting.value) return
        if (input.isBlank()) {
            disconnect()
            onDone()
            return
        }
        _connecting.value = true
        viewModelScope.launch {
            val result = imdbSync.connect(input)
            _connecting.value = false
            result
                .onSuccess { account ->
                    _messages.tryEmit(ImdbSettingsMessage.Connected(account))
                    onDone()
                }
                .onFailure { error ->
                    val reason = (error as? ImdbException)?.reason ?: ImdbException.Reason.FAILED
                    _messages.tryEmit(ImdbSettingsMessage.Failed(reason))
                    // Linked, but its lists couldn't be read yet: the dialog still closes.
                    if (imdbSync.account.value.isConnected) onDone()
                }
        }
    }

    fun syncNow() {
        if (account.value.isSyncing) return
        viewModelScope.launch {
            imdbSync.sync(force = true)
                .onSuccess {
                    val reason = imdbSync.account.value.error
                    _messages.tryEmit(if (reason != null) ImdbSettingsMessage.Failed(reason) else ImdbSettingsMessage.Synced)
                }
                .onFailure { error ->
                    _messages.tryEmit(ImdbSettingsMessage.Failed((error as? ImdbException)?.reason ?: ImdbException.Reason.FAILED))
                }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            imdbSync.disconnect()
            _messages.tryEmit(ImdbSettingsMessage.Disconnected)
        }
    }
}
