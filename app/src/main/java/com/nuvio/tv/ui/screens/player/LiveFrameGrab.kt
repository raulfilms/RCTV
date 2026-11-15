package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * RCTV (owner): the picture a live channel is showing as the player is left, small, for the Guide:
 * it stays on screen behind the Guide until the channel plays there again, so the picture never
 * goes away. Null when it can't be read (no video surface, a protected one, too slow).
 */
internal object LiveFrameGrab {

    /** At most this wide (the Guide covers it in part; small to keep the TV's memory). */
    private const val MAX_WIDTH = 960

    /** Leaving the player never waits longer than this for it. */
    private const val GRAB_MS = 250L

    suspend fun grab(view: PlayerView?): Bitmap? {
        val surface = view?.videoSurfaceView as? SurfaceView ?: return null
        val width = surface.width
        val height = surface.height
        if (width <= 0 || height <= 0 || surface.holder?.surface?.isValid != true) return null
        val scale = minOf(1f, MAX_WIDTH.toFloat() / width)
        val bitmap = try {
            Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        val result = withTimeoutOrNull(GRAB_MS) {
            suspendCancellableCoroutine { done ->
                try {
                    PixelCopy.request(surface, bitmap, { code -> if (done.isActive) done.resume(code) }, Handler(Looper.getMainLooper()))
                } catch (_: Exception) {
                    if (done.isActive) done.resume(PixelCopy.ERROR_UNKNOWN)
                }
            }
        }
        // Not recycled by hand when it didn't come in time: the copy may still be writing to it.
        return bitmap.takeIf { result == PixelCopy.SUCCESS }
    }
}
