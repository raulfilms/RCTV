package com.nuvio.tv.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.imdb.ImdbGraphqlClient

private const val SIGN_IN_URL = "https://www.imdb.com/registration/signin"
private const val IMDB_URL = "https://www.imdb.com"

/** Pages that show (or lead to) the signed-in user's own profile, whose link has their id. */
private val OWN_PROFILE_PAGES = listOf("https://www.imdb.com/profile", "https://www.imdb.com/list/watchlist")

/** IMDb's desktop pages: they fit a TV better than its phone ones. */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

/**
 * IMDb's own sign-in page, full screen (IMDb, Amazon, Google or Apple, as on imdb.com). Once
 * signed in, it opens the user's profile to learn their id, and hands back the sign-in's cookies
 * and that id. It starts signed out and forgets the sign-in when it closes, so it's kept only by
 * the profile it was made for. Back closes it.
 */
@Composable
internal fun ImdbSignInDialog(
    onSignedIn: (cookies: String, profileKey: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val tracker = remember { SignInTracker(onSignedIn) }
    val holder = remember { arrayOfNulls<WebView>(1) }

    DisposableEffect(Unit) {
        onDispose {
            holder[0]?.let { view ->
                view.stopLoading()
                view.destroy()
            }
            holder[0] = null
            forgetSignIn()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    try {
                        signInWebView(context, tracker).also { holder[0] = it }
                    } catch (e: Exception) {
                        // No WebView on this TV (missing or turned off).
                        unavailableView(context)
                    }
                }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun signInWebView(context: Context, tracker: SignInTracker): WebView {
    val cookieManager = CookieManager.getInstance()
    // Signed out to start: no one else's sign-in carries over.
    cookieManager.removeAllCookies(null)
    cookieManager.setAcceptCookie(true)
    return WebView(context).apply {
        cookieManager.setAcceptThirdPartyCookies(this, true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = DESKTOP_USER_AGENT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        isFocusable = true
        isFocusableInTouchMode = true
        webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                tracker.onUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                tracker.onPageFinished(view, url)
            }
        }
        loadUrl(SIGN_IN_URL)
        requestFocus(View.FOCUS_DOWN)
    }
}

private fun unavailableView(context: Context): View = TextView(context).apply {
    text = context.getString(R.string.imdb_sign_in_unavailable)
    setTextColor(android.graphics.Color.WHITE)
    textSize = 20f
    gravity = Gravity.CENTER
    setPadding(96, 96, 96, 96)
}

/** The app's WebView keeps nothing of IMDb once the sign-in is handed over (or abandoned). */
private fun forgetSignIn() {
    runCatching {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }
}

/** Watches the sign-in: signed in (IMDb's cookies), then the user's own profile link (their id). */
private class SignInTracker(private val onSignedIn: (String, String?) -> Unit) {
    private var finished = false
    private var profilePagesTried = 0

    private fun cookies(): String = runCatching { CookieManager.getInstance().getCookie(IMDB_URL) }.getOrNull().orEmpty()

    /** A page opened (redirects too): their profile, with their id, ends it. */
    fun onUrl(url: String?) {
        if (finished || url == null || "/user/" !in url) return
        val cookies = cookies()
        if (!ImdbGraphqlClient.isSignedIn(cookies)) return
        ImdbGraphqlClient.profileKeyOf(url)?.let { finish(cookies, it) }
    }

    /** Signed in: on to their profile (to learn their id); no id there either, it ends without one. */
    fun onPageFinished(view: WebView, url: String?) {
        if (finished) return
        onUrl(url)
        if (finished) return
        val cookies = cookies()
        if (!ImdbGraphqlClient.isSignedIn(cookies)) return
        if (profilePagesTried < OWN_PROFILE_PAGES.size) {
            view.loadUrl(OWN_PROFILE_PAGES[profilePagesTried])
            profilePagesTried++
        } else {
            finish(cookies, null)
        }
    }

    private fun finish(cookies: String, profileKey: String?) {
        finished = true
        onSignedIn(cookies, profileKey)
    }
}
