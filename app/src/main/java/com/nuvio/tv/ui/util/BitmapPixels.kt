package com.nuvio.tv.ui.util

import android.graphics.Bitmap
import android.os.Build

/**
 * RCTV (MEM-01): this bitmap, or a software copy of it when it's a hardware bitmap (whose pixels
 * can't be read or drawn on a software canvas). Coil hands transformations software bitmaps; this
 * keeps a transformation working even if one ever arrives as hardware.
 */
internal fun Bitmap.withReadablePixels(): Bitmap {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || config != Bitmap.Config.HARDWARE) return this
    return runCatching { copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: this
}
