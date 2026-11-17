package com.nuvio.tv.core.rctvcontrol

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.nuvio.tv.core.auth.AccountLocalDataResetService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RCTV (owner): every RCTV Control account is its own: its profiles, Library, Continue Watching,
 * history, IMDb and Trakt connections, favorites and settings are that account's alone, and no other
 * account ever sees them. A TV that joins an account (its code entered) starts clean: what the TV
 * had (another account's, or from before) is cleared before anything syncs, so none of it goes up
 * to the new account; then the account's own things come down from the website. Then, before
 * anything else, "Who's watching?" asks for the profile (to name it).
 */
@Singleton
class RctvAccountDataGuard @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _freshAccount = MutableStateFlow(prefs.getBoolean(KEY_FRESH_ACCOUNT, false))

    /** True from joining an account until the profile is chosen (or named) on "Who's watching?". */
    val freshAccount: StateFlow<Boolean> = _freshAccount.asStateFlow()

    /** The profile was chosen after joining the account. */
    fun freshAccountDone() {
        if (!_freshAccount.value) return
        _freshAccount.value = false
        prefs.edit().putBoolean(KEY_FRESH_ACCOUNT, false).apply()
    }

    /**
     * Clears everything personal this TV holds (all profiles' Library, watched titles and progress,
     * Continue Watching, IMDb and Trakt sign-ins, favorites, search history, recent channels, the
     * launcher's rows, what was agreed with the last account), as signing out of an account does.
     * The TV's own setup (its picture and sound settings) stays.
     */
    suspend fun startClean() {
        try {
            val reset = EntryPointAccessors.fromApplication(context, Entry::class.java).accountLocalDataReset()
            reset.clearAfterSignOut()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Profiles' data couldn't all be cleared: ${e.javaClass.simpleName}")
        }
        withContext(Dispatchers.IO) {
            ACCOUNT_PREFS.forEach { name ->
                runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
            }
        }
        // An IMDb (or other) sign-in made in a web page here.
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
        _freshAccount.value = true
        prefs.edit().putBoolean(KEY_FRESH_ACCOUNT, true).apply()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun accountLocalDataReset(): AccountLocalDataResetService
    }

    private companion object {
        const val TAG = "RctvAccountData"
        const val PREFS = "rctv_account_data"
        const val KEY_FRESH_ACCOUNT = "fresh_account"

        /** What the TV agreed with its last account, and its other personal lists kept outside profiles. */
        val ACCOUNT_PREFS = listOf(
            "rctv_watch_sync",
            "rctv_control_profiles",
            "rctv_profile_prefs_sync",
            "rctv_control_addons",
            "rctv_addon_roles",
            "rctv_control_public",
            "imdb_tmdb_links",
            "member_catalogs",
            "external_playback_pending"
        )
    }
}
