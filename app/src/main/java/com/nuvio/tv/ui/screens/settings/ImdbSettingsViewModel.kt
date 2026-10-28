package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.ImdbLinkReply
import com.nuvio.tv.core.server.ImdbLinkServer
import com.nuvio.tv.core.server.ImdbLinkStatus
import com.nuvio.tv.core.server.ImdbLinkWebPage
import com.nuvio.tv.data.imdb.ImdbAccount
import com.nuvio.tv.data.imdb.ImdbSyncService
import com.nuvio.tv.data.remote.imdb.ImdbException
import com.nuvio.tv.data.remote.imdb.ImdbGraphqlClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What happened when the user linked their IMDb account or synced it. */
sealed interface ImdbSettingsMessage {
    data class Connected(val account: ImdbAccount) : ImdbSettingsMessage
    data class Failed(val reason: ImdbException.Reason) : ImdbSettingsMessage
    data object Synced : ImdbSettingsMessage
    data object Disconnected : ImdbSettingsMessage
    data object SignedIn : ImdbSettingsMessage
    data object SignedOut : ImdbSettingsMessage
}

/**
 * RCTV: the QR a phone scans to link IMDb (it opens the TV's little IMDb page), shown while IMDb's
 * settings are open; [error] when the TV can't serve it (no network...).
 */
data class ImdbPhoneLink(val qr: Bitmap? = null, val url: String? = null, val error: Int? = null)

private const val TAG = "ImdbSettings"
/** How long the phone waits for the TV to link or sync before it's told the TV is still at it. */
private const val PHONE_WAIT_MS = 45_000L

@HiltViewModel
class ImdbSettingsViewModel @Inject constructor(
    private val imdbSync: ImdbSyncService,
    profileManager: com.nuvio.tv.core.profile.ProfileManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    /** The account linked to the profile that's on (each profile links its own). */
    val account: StateFlow<ImdbAccount> = imdbSync.account

    /** The name of the profile that's on: the account is linked for it only. */
    val profileName: StateFlow<String?> = combine(profileManager.profiles, profileManager.activeProfileId) { profiles, activeId ->
        profiles.firstOrNull { it.id == activeId }?.name?.takeIf { it.isNotBlank() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, profileManager.activeProfile?.name)

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

    /** Signed in on IMDb's page: keeps the sign-in for this profile (and links its account). */
    fun signedIn(cookies: String, profileKey: String?) {
        if (_connecting.value) return
        _connecting.value = true
        viewModelScope.launch {
            val result = imdbSync.connectSignedIn(cookies, profileKey)
            _connecting.value = false
            result
                .onSuccess { _messages.tryEmit(ImdbSettingsMessage.SignedIn) }
                .onFailure { error ->
                    _messages.tryEmit(ImdbSettingsMessage.Failed((error as? ImdbException)?.reason ?: ImdbException.Reason.FAILED))
                }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            imdbSync.signOut()
            _messages.tryEmit(ImdbSettingsMessage.SignedOut)
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

    // --- RCTV: linking from the phone (the QR) ---

    private var phoneServer: ImdbLinkServer? = null
    private val _phoneLink = MutableStateFlow<ImdbPhoneLink?>(null)
    /** The QR the phone scans to link IMDb; null until it's ready. */
    val phoneLink: StateFlow<ImdbPhoneLink?> = _phoneLink.asStateFlow()

    /** Opens the TV's little IMDb page for the phone (while IMDb's settings are on screen). */
    fun startPhoneLink() {
        if (phoneServer != null) return
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _phoneLink.value = ImdbPhoneLink(error = R.string.imdb_phone_no_network)
            return
        }
        val server = ImdbLinkServer.startOnAvailablePort(
            status = ::phoneStatus,
            link = ::linkFromPhone,
            sync = ::syncFromPhone,
            unlink = ::unlinkFromPhone,
            context = context
        )
        if (server == null) {
            _phoneLink.value = ImdbPhoneLink(error = R.string.error_server_ports_unavailable)
            return
        }
        phoneServer = server
        val url = server.access.shareUrl("http://$ip:${server.listeningPort}")
        _phoneLink.value = ImdbPhoneLink(qr = QrCodeGenerator.generate(url, 512), url = url)
    }

    fun stopPhoneLink() {
        phoneServer?.stop()
        phoneServer = null
        _phoneLink.value = null
    }

    override fun onCleared() {
        stopPhoneLink()
        super.onCleared()
    }

    /** The app's strings in the language chosen in the app (what the phone shows). */
    private fun texts(): Context = ImdbLinkWebPage.localized(context)

    private fun phoneStatus(): ImdbLinkStatus {
        val account = imdbSync.account.value
        val texts = texts()
        val details = if (account.isConnected) {
            val synced = when {
                account.isSyncing -> texts.getString(R.string.imdb_syncing)
                account.lastSyncMs > 0 -> texts.getString(
                    R.string.imdb_last_sync,
                    DateUtils.getRelativeTimeSpanString(account.lastSyncMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                )
                else -> texts.getString(R.string.imdb_never_synced)
            }
            texts.getString(R.string.imdb_sync_counts, account.watchlistCount, account.ratedCount) + " · " + synced
        } else {
            null
        }
        return ImdbLinkStatus(
            linked = account.isConnected,
            title = if (account.isConnected) {
                texts.getString(R.string.web_imdb_linked_as, account.nickname ?: account.userId.orEmpty())
            } else {
                texts.getString(R.string.imdb_not_linked)
            },
            details = details,
            problem = account.error?.takeIf { account.isConnected }?.let { texts.getString(imdbErrorText(it)) },
            syncing = account.isSyncing,
            profile = profileName.value
        )
    }

    private fun phoneReply(ok: Boolean, message: String) = ImdbLinkReply(ok, message, phoneStatus())

    /** Links the account pasted on the phone (on the server's thread: it waits for the TV). */
    private fun linkFromPhone(pasted: String): ImdbLinkReply {
        val texts = texts()
        if (pasted.isBlank()) return phoneReply(false, texts.getString(R.string.web_imdb_error_empty))
        val input = runBlocking { imdbKeyIn(pasted) }
            ?: return phoneReply(false, texts.getString(R.string.imdb_error_bad_id))
        if (_connecting.value) return phoneReply(false, texts.getString(R.string.imdb_linking))
        _connecting.value = true
        val work = viewModelScope.async {
            try {
                imdbSync.connect(input)
            } finally {
                _connecting.value = false
            }
        }
        val result = runBlocking { withTimeoutOrNull(PHONE_WAIT_MS) { work.await() } }
            ?: return phoneReply(true, texts.getString(R.string.web_imdb_still_working))
        val account = imdbSync.account.value
        return result.fold(
            onSuccess = { linked ->
                _messages.tryEmit(ImdbSettingsMessage.Connected(linked))
                phoneReply(
                    true,
                    if (linked.error == ImdbException.Reason.PRIVATE) {
                        texts.getString(R.string.imdb_error_private)
                    } else {
                        texts.getString(R.string.imdb_connected_toast, linked.ratedCount, linked.watchlistCount)
                    }
                )
            },
            onFailure = { error ->
                val reason = (error as? ImdbException)?.reason ?: ImdbException.Reason.FAILED
                _messages.tryEmit(ImdbSettingsMessage.Failed(reason))
                // Linked, but its lists couldn't all be read yet.
                val linked = account.isConnected && account.input == input.trim()
                phoneReply(linked, texts.getString(imdbErrorText(reason)))
            }
        )
    }

    private fun syncFromPhone(): ImdbLinkReply {
        val texts = texts()
        if (!imdbSync.account.value.isConnected) return phoneReply(false, texts.getString(R.string.imdb_not_linked))
        val work = viewModelScope.async { imdbSync.sync(force = true) }
        val result = runBlocking { withTimeoutOrNull(PHONE_WAIT_MS) { work.await() } }
            ?: return phoneReply(true, texts.getString(R.string.web_imdb_still_working))
        val reason = result.exceptionOrNull()?.let { (it as? ImdbException)?.reason ?: ImdbException.Reason.FAILED }
            ?: imdbSync.account.value.error
        _messages.tryEmit(if (reason != null) ImdbSettingsMessage.Failed(reason) else ImdbSettingsMessage.Synced)
        return if (reason != null) {
            phoneReply(result.isSuccess, texts.getString(imdbErrorText(reason)))
        } else {
            phoneReply(true, texts.getString(R.string.imdb_synced_toast))
        }
    }

    private fun unlinkFromPhone(): ImdbLinkReply {
        runBlocking { viewModelScope.async { imdbSync.disconnect() }.await() }
        _messages.tryEmit(ImdbSettingsMessage.Disconnected)
        return phoneReply(true, texts().getString(R.string.imdb_unlinked_toast))
    }

    /**
     * What to link from what was pasted on the phone: the profile's link or id as it is, or found in
     * a longer shared text, or behind a short IMDb link (imdb.to/…, a list's page) once opened.
     */
    private suspend fun imdbKeyIn(pasted: String): String? {
        val text = pasted.trim()
        if (ImdbGraphqlClient.profileKeyOf(text) != null) return text
        Regex("""https?://\S+""").findAll(text).map { it.value.trimEnd('.', ',', ')', '"', '\'') }.forEach { url ->
            if (ImdbGraphqlClient.profileKeyOf(url) != null) return url
        }
        Regex("""\bur\d{4,12}\b""", RegexOption.IGNORE_CASE).find(text)?.let { return it.value.lowercase() }
        val imdbUrl = Regex("""https?://\S+""").findAll(text).map { it.value }
            .firstOrNull { Regex("""^https?://([a-z0-9-]+\.)*imdb\.(com|to)/""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: return null
        return withContext(Dispatchers.IO) { profileBehind(imdbUrl) }
    }

    /** The profile an IMDb link leads to (where it ends up, or the owner its page names). */
    private fun profileBehind(url: String): String? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", BROWSER_AGENT)
                setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            }
            try {
                connection.responseCode
                val landed = connection.url.toString()
                ImdbGraphqlClient.profileKeyOf(landed) ?: run {
                    val page = connection.inputStream.use { stream ->
                        val bytes = ByteArray(PAGE_LIMIT)
                        var read = 0
                        while (read < PAGE_LIMIT) {
                            val n = stream.read(bytes, read, PAGE_LIMIT - read)
                            if (n <= 0) break
                            read += n
                        }
                        String(bytes, 0, read, Charsets.UTF_8)
                    }
                    Regex("""/user/(ur\d{4,12})""").find(page)?.groupValues?.get(1)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the IMDb link: ${e.message}")
            null
        }
    }

    private companion object {
        const val PAGE_LIMIT = 1_500_000
        const val BROWSER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
