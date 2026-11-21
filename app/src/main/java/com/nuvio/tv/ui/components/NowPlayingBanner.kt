package com.nuvio.tv.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.playback.PlaybackSession

/** RCTV (owner, as YouTube TV): the Now Playing bar's height; the app's pages start under it. */
val NowPlayingBarHeight = 56.dp

/**
 * RCTV (owner, as YouTube TV): Home or Movies & TV moved down off its banner: the Now Playing bar
 * goes away (what plays goes on being heard) and is back as soon as focus is up on the banner again.
 * Set by those pages only ([NowPlayingBarFollowsBanner]).
 */
/**
 * RCTV (owner): OK held on the Now Playing bar closed what plays; until that OK is let go, its key
 * presses go nowhere (the page doesn't get them: they pressed the banner under the bar).
 */
object NowPlayingBarKeys {
    @Volatile
    var okHeldAfterClose: Boolean = false

    /** True when [event] is the rest of that held OK (swallowed); the release ends it. */
    fun swallow(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (!okHeldAfterClose) return false
        if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) return false
        if (event.type == KeyEventType.KeyUp) {
            okHeldAfterClose = false
            return true
        }
        // A new press (its release went astray): it's the page's again.
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
            okHeldAfterClose = false
            return false
        }
        return true
    }
}

/**
 * RCTV (owner): a page's full-screen panel (a program's details) shown above everything, the Now
 * Playing bar included; the bar stays where it is, under it. The page shows it with
 * [ShowAboveNowPlayingBar] while it's open; MainActivity draws it with [AboveNowPlayingBarHost].
 */
object AboveNowPlayingBar {
    internal var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

/** RCTV: shows [content] above the whole screen while this is in the page (see [AboveNowPlayingBar]). */
@Composable
fun ShowAboveNowPlayingBar(content: @Composable () -> Unit) {
    val latest = androidx.compose.runtime.rememberUpdatedState(content)
    DisposableEffect(Unit) {
        val slot: @Composable () -> Unit = { latest.value() }
        AboveNowPlayingBar.content = slot
        onDispose { if (AboveNowPlayingBar.content === slot) AboveNowPlayingBar.content = null }
    }
}

/** RCTV: where MainActivity draws a page's panel, above the Now Playing bar. */
@Composable
fun AboveNowPlayingBarHost() {
    AboveNowPlayingBar.content?.invoke()
}

object NowPlayingBarScroll {
    var pageScrolled by mutableStateOf(false)
        private set
    private var owner: Any? = null

    fun set(page: Any, scrolled: Boolean) {
        owner = page
        pageScrolled = scrolled
    }

    /** The page went away: only the page that set it last clears it (not one leaving after another came). */
    fun release(page: Any) {
        if (owner !== page) return
        owner = null
        pageScrolled = false
    }
}

/** How long the bar takes to slide away (and back), with the page moving under it. */
const val BAR_SLIDE_MS = 220

/**
 * RCTV (owner, as YouTube TV): on a page with a banner at its top, the Now Playing bar slides away once
 * focus moves down off the banner, and is there again as soon as focus is back up on the banner, the
 * page then back at its very top, whole under the bar (never left halfway with nothing above to move
 * Up to). Where focus is decides, not how far the page moved: the bar taking its room back never hides
 * it again. Focus on the bar itself (or on the menu) changes nothing. Only while something plays.
 */
@Composable
fun NowPlayingBarFollowsBanner(
    listState: LazyListState,
    bannerShown: Boolean,
    pageHasFocus: Boolean,
    bannerHasFocus: Boolean
) {
    val nowPlaying by PlaybackSession.nowPlaying.collectAsState()
    val playing = nowPlaying != null
    val page = remember { Any() }
    LaunchedEffect(playing, bannerShown, pageHasFocus, bannerHasFocus) {
        if (!playing || !bannerShown) {
            NowPlayingBarScroll.set(page, false)
            return@LaunchedEffect
        }
        if (!pageHasFocus) return@LaunchedEffect
        NowPlayingBarScroll.set(page, !bannerHasFocus)
        if (!bannerHasFocus) return@LaunchedEffect
        // Back up on the banner: the page goes all the way back to its top (moving onto the banner's
        // buttons only brings the buttons into view, which left the banner cut).
        repeat(3) {
            withFrameNanos { }
            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) return@LaunchedEffect
            runCatching { listState.animateScrollToItem(0, 0) }
        }
    }
    DisposableEffect(Unit) {
        onDispose { NowPlayingBarScroll.release(page) }
    }
}

/**
 * RCTV (owner, as YouTube TV): the Now Playing bar across the top of the app's pages while a
 * channel, movie or episode goes on playing behind the app (the player was left with Back): a black
 * bar with what plays behind it (darkened, and only inside the bar: the page below never shows it),
 * the channel's logo at the left and what's on in the middle. Up reaches
 * the name (it turns white, as YouTube TV's); OK, or Play on the remote, opens the full player again
 * on the same playback (nothing restarts); OK held closes it (the bar goes away). Down goes back
 * to the page. It stays on every page, the page whole under it; moving down Home or Movies & TV
 * it slides away and is back up on the banner ([hidden]). It counts as this TV playing for RCTV Control, as the player did.
 */
@Composable
fun NowPlayingBannerHost(
    navController: NavController,
    onPlayerScreen: Boolean,
    /** RCTV (owner): focus back on the page, where it was before Up to the bar (true when it went). */
    onBackToPage: () -> Boolean = { false },
    /** Focus moved down off the page's banner: the bar slides away (the playback goes on). */
    hidden: Boolean = false,
    /** RCTV (owner): Up from the top of any page lands on the name through this. */
    nameFocusRequester: FocusRequester? = null,
    /** False where the page itself shows the playback (the Guide, behind it): the bar doesn't take it. */
    showVideo: Boolean = true,
    modifier: Modifier = Modifier
) {
    val nowPlaying by PlaybackSession.nowPlaying.collectAsState()
    val current = nowPlaying ?: return
    val context = LocalContext.current
    RctvPlaybackGuard(
        kind = current.guardKind,
        title = current.guardTitle,
        onStop = { messageRes ->
            PlaybackSession.stopMinimized()
            Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show()
        }
    )
    if (onPlayerScreen) return
    val focusManager = LocalFocusManager.current
    val player = remember(current, showVideo) { if (showVideo) PlaybackSession.minimizedPlayer() else null }
    AnimatedVisibility(
        visible = !hidden,
        enter = slideInVertically(animationSpec = tween(BAR_SLIDE_MS)) { -it } + fadeIn(tween(BAR_SLIDE_MS)),
        exit = slideOutVertically(animationSpec = tween(BAR_SLIDE_MS)) { -it } + fadeOut(tween(BAR_SLIDE_MS)),
        modifier = modifier
    ) {
        NowPlayingBar(
            nowPlaying = current,
            player = player,
            onClick = { PlaybackSession.resume(navController) },
            // RCTV (owner): OK held on the name closes what plays (where it was is kept for
            // Continue Watching) and the bar goes away; focus goes back to the page first.
            onLongClick = {
                // The rest of this press (OK still held, then let go) isn't for the page: it used to
                // press what's under the bar (the banner) once the bar was gone.
                NowPlayingBarKeys.okHeldAfterClose = true
                onBackToPage()
                PlaybackSession.stopMinimized()
            },
            // RCTV (owner): never a dead end: Down goes back to the page (where it was), as do Left
            // and Right when nothing is beside the name.
            nameModifier = Modifier
                .then(if (nameFocusRequester != null) Modifier.focusRequester(nameFocusRequester) else Modifier)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            if (!onBackToPage()) focusManager.moveFocus(FocusDirection.Down)
                            true
                        }
                        Key.DirectionLeft, Key.DirectionRight -> {
                            val direction = if (event.key == Key.DirectionLeft) FocusDirection.Left else FocusDirection.Right
                            if (!focusManager.moveFocus(direction)) onBackToPage()
                            true
                        }
                        else -> false
                    }
                }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NowPlayingBar(
    nowPlaying: PlaybackSession.NowPlaying,
    player: androidx.media3.common.Player?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    nameModifier: Modifier,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NowPlayingBarHeight)
            .clipToBounds()
            .background(Color.Black)
    ) {
        // What plays, inside the bar only (darkened so the bar reads, as YouTube TV's); the page
        // below never shows it.
        if (player != null) {
            com.nuvio.tv.ui.screens.livetv.LiveTvSessionVideo(
                player = player,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                inBar = true
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.72f))
                        )
                    )
            )
        }
        // The channel's logo at the left (a movie or an episode has none there).
        val logo = nowPlaying.art?.takeIf { nowPlaying.isLive && it.isNotBlank() }
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = nowPlaying.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
                    .height(24.dp)
                    .widthIn(max = 96.dp)
            )
        }
        // What's on, in the middle: white when Up reaches it.
        val name = if (nowPlaying.isLive) {
            nowPlaying.subtitle?.takeIf { it.isNotBlank() } ?: nowPlaying.title
        } else {
            listOfNotNull(nowPlaying.title, nowPlaying.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
        }
        val shape = RoundedCornerShape(50)
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = nameModifier
                .align(Alignment.Center)
                .height(38.dp)
                .widthIn(max = 620.dp),
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
        ) {
            Box(modifier = Modifier.height(38.dp).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
