package com.nuvio.tv.ui.components

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.R
import com.nuvio.tv.data.trailer.YouTubeVideo

/**
 * RCTV Smart Player: the only way RCTV plays a YouTube video (a trailer, a game's recap, a live
 * Banana Ball game). YouTube's official IFrame Player API in a WebView: YouTube chooses the
 * quality and the sound; RCTV never reads the video's own stream addresses.
 *
 * - One WebView, made when this is shown and destroyed when it leaves (nothing plays in the
 *   background; paused when the app is).
 * - No YouTube controls: the screen showing it drives the player through the API (play, pause,
 *   seek), with the remote like RCTV's other players; [onRemoteKey] gets every key first.
 * - A video its owner doesn't let other sites show (errors 101 and 150): [openYouTubeWhenBlocked]
 *   opens it in the YouTube app, the official way to watch it; then [onError].
 * - When the WebView's own process stops (the TV ran out of memory), the app stays open:
 *   [onError] with [ERROR_WEBVIEW_GONE].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeIframePlayer(
    videoId: String,
    isPlaying: Boolean,
    isPaused: Boolean = false,
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    openYouTubeWhenBlocked: Boolean = false,
    onStarted: () -> Unit = {},
    onEnded: () -> Unit = {},
    onError: (code: Int) -> Unit = {},
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    modifier: Modifier = Modifier
) {
    if (!YouTubeVideo.isId(videoId)) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnStarted by rememberUpdatedState(onStarted)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnProgress by rememberUpdatedState(onProgressChanged)
    val currentOnRemoteKey by rememberUpdatedState(onRemoteKey)
    val currentOpenWhenBlocked by rememberUpdatedState(openYouTubeWhenBlocked)
    val wantsPlay by rememberUpdatedState(isPlaying && !isPaused)
    var webView by remember(videoId) { mutableStateOf<WebView?>(null) }
    var started by remember(videoId) { mutableStateOf(false) }

    val bridge = remember(videoId) {
        YouTubeBridge(
            onReady = { webView?.command(if (wantsPlay) "play()" else "pause()") },
            onState = { state ->
                when (state) {
                    STATE_PLAYING -> if (!started) {
                        started = true
                        currentOnStarted()
                    }
                    STATE_ENDED -> currentOnEnded()
                }
            },
            onError = { code ->
                if (currentOpenWhenBlocked && code in BLOCKED_ERRORS) openInYouTube(context, videoId)
                currentOnError(code)
            },
            onProgress = { position, duration ->
                // Playing (its time moves) even if the "playing" state was missed: shown.
                if (position > 0 && !started) {
                    started = true
                    currentOnStarted()
                }
                currentOnProgress(position, duration)
            }
        )
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // RCTV: its own hardware layer. Without it some TVs (the Mi Box) play the sound
                // with no picture.
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                isFocusable = true
                isFocusableInTouchMode = true
                keepScreenOn = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    // The player stays the page: a tap on YouTube's logo doesn't open youtube.com here.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        // The WebView can't be used again: dropped, and the screen is told.
                        MAIN.post {
                            if (webView === view) webView = null
                            view.release()
                            currentOnError(ERROR_WEBVIEW_GONE)
                        }
                        return true
                    }
                }
                addJavascriptInterface(bridge, BRIDGE_NAME)
                setOnKeyListener { _, keyCode, event ->
                    if (currentOnRemoteKey(keyCode, event.action, event.repeatCount)) return@setOnKeyListener true
                    // Back leaves the screen; every other remote key is RCTV's, not the page's.
                    keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE
                }
                loadDataWithBaseURL(ORIGIN, pageFor(videoId, muted), "text/html", "UTF-8", null)
                webView = this
            }
        },
        onRelease = { view ->
            if (webView === view) webView = null
            view.release()
        },
        // Black over it until it plays (not YouTube's loading screen). Drawn on top rather than
        // fading the WebView: a WebView drawn see-through can show no picture.
        modifier = modifier.drawWithContent {
            drawContent()
            if (!started) drawRect(Color.Black)
        }
    )

    LaunchedEffect(webView, isPlaying, isPaused) {
        webView?.command(if (isPlaying && !isPaused) "play()" else "pause()")
    }
    LaunchedEffect(webView, muted) {
        webView?.command("mute(${if (muted) "true" else "false"})")
    }
    LaunchedEffect(webView, seekRequestToken) {
        if (seekRequestToken > 0) webView?.command("seekBy(${seekDeltaMs})")
    }
    DisposableEffect(lifecycleOwner, webView) {
        val view = webView ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    view.command("pause()")
                    runCatching { view.onPause() }
                }
                Lifecycle.Event.ON_RESUME -> {
                    runCatching { view.onResume() }
                    if (wantsPlay) view.command("play()")
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { runCatching { lifecycleOwner.lifecycle.removeObserver(observer) } }
    }
}

/** The API's player states (YT.PlayerState). */
private const val STATE_ENDED = 0
private const val STATE_PLAYING = 1

/** The IFrame API's errors for a video its owner doesn't allow on other sites. */
private val BLOCKED_ERRORS = setOf(101, 150)

/** The WebView's own process stopped (not a YouTube error). */
const val ERROR_WEBVIEW_GONE = -2

private const val BRIDGE_NAME = "RctvYouTube"

/**
 * The page's origin: YouTube asks an embedded player who embeds it (its referrer); RCTV's own
 * server's address, which RCTV owns.
 */
private val ORIGIN: String get() = com.nuvio.tv.core.network.RctvServers.CORE + "/"

private val MAIN = Handler(Looper.getMainLooper())

private fun WebView.command(js: String) {
    runCatching { evaluateJavascript("window.rctv && window.rctv.$js;", null) }
}

private fun WebView.release() {
    runCatching { stopLoading() }
    runCatching { loadUrl("about:blank") }
    runCatching { removeJavascriptInterface(BRIDGE_NAME) }
    runCatching { (parent as? ViewGroup)?.removeView(this) }
    runCatching { destroy() }
}

private fun openInYouTube(context: Context, videoId: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(YouTubeVideo.watchUrl(videoId)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        Toast.makeText(context, R.string.youtube_opening_app, Toast.LENGTH_SHORT).show()
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.youtube_app_missing, Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.youtube_app_missing, Toast.LENGTH_LONG).show()
    }
}

/** What the page tells the app (on WebView's own thread: passed to the main thread). */
private class YouTubeBridge(
    private val onReady: () -> Unit,
    private val onState: (Int) -> Unit,
    private val onError: (Int) -> Unit,
    private val onProgress: (Long, Long) -> Unit
) {
    @JavascriptInterface
    fun ready() {
        MAIN.post { onReady() }
    }

    @JavascriptInterface
    fun state(value: Int) {
        MAIN.post { onState(value) }
    }

    @JavascriptInterface
    fun error(code: Int) {
        MAIN.post { onError(code) }
    }

    @JavascriptInterface
    fun progress(positionMs: Long, durationMs: Long) {
        MAIN.post { onProgress(positionMs.coerceAtLeast(0L), durationMs.coerceAtLeast(0L)) }
    }
}

/**
 * The page: YouTube's IFrame API (https://developers.google.com/youtube/iframe_api_reference),
 * the player filling it, YouTube's own controls and keyboard off (the app drives it), and
 * `window.rctv` for the app's commands. [videoId] was checked with [YouTubeVideo.isId].
 */
private fun pageFor(videoId: String, muted: Boolean): String {
    val mute = if (muted) 1 else 0
    val origin = com.nuvio.tv.core.network.RctvServers.CORE
    return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}#player{position:absolute;top:0;left:0;width:100%;height:100%}</style>
</head><body><div id="player"></div><script>
var player=null, ready=false, wantPlay=true;
window.rctv={
  play:function(){wantPlay=true;if(ready)player.playVideo();},
  pause:function(){wantPlay=false;if(ready)player.pauseVideo();},
  mute:function(m){if(!ready)return;if(m)player.mute();else player.unMute();},
  seekBy:function(ms){if(!ready)return;var t=player.getCurrentTime()+ms/1000;player.seekTo(t<0?0:t,true);}
};
function onYouTubeIframeAPIReady(){
  player=new YT.Player('player',{width:'100%',height:'100%',videoId:'$videoId',
    playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,rel:0,playsinline:1,iv_load_policy:3,mute:$mute,origin:'$origin'},
    events:{
      onReady:function(e){ready=true;RctvYouTube.ready();if(wantPlay)e.target.playVideo();},
      onStateChange:function(e){RctvYouTube.state(e.data);},
      onError:function(e){RctvYouTube.error(e.data);}
    }});
}
setInterval(function(){
  if(!ready||!player.getCurrentTime)return;
  RctvYouTube.progress(Math.floor(player.getCurrentTime()*1000),Math.floor((player.getDuration()||0)*1000));
},500);
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>
""".trimIndent()
}
