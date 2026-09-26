package com.nuvio.tv.ui.util

import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent

@Composable
fun rememberLongPressKeyTracker(): LongPressKeyTracker = remember { LongPressKeyTracker() }

private fun isSelectOrMenuKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_MENU

/**
 * Attaches a "hold OK/Enter (or press Menu)" long-press gesture to a focusable card, without
 * disturbing its normal click behavior on a short press. Mirrors the pattern used by
 * ContentCard's own long-press handling, factored out so any TV-focusable Surface/Card can opt in
 * with a single modifier instead of re-implementing the key tracking. A `null` [onLongPress]
 * leaves the modifier chain untouched.
 */
@Composable
fun Modifier.longPressable(onLongPress: (() -> Unit)?): Modifier {
    if (onLongPress == null) return this
    val longPressKeyTracker = rememberLongPressKeyTracker()
    var longPressTriggered by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { keyEvent ->
        val native = keyEvent.nativeKeyEvent
        if (native.action == KeyEvent.ACTION_DOWN && native.keyCode == KeyEvent.KEYCODE_MENU) {
            longPressTriggered = true
            onLongPress()
            return@onPreviewKeyEvent true
        }
        if (longPressKeyTracker.handle(native, ::isSelectOrMenuKey) {
                longPressTriggered = true
                onLongPress()
            }
        ) {
            if (native.action == KeyEvent.ACTION_UP) longPressTriggered = false
            return@onPreviewKeyEvent true
        }
        if (native.action == KeyEvent.ACTION_UP && longPressTriggered && isSelectOrMenuKey(native.keyCode)) {
            longPressTriggered = false
            return@onPreviewKeyEvent true
        }
        false
    }
}

class LongPressKeyTracker(
    private val timeoutMillis: Long = ViewConfiguration.getLongPressTimeout().toLong()
) {
    private var pressedKeyCode: Int? = null
    private var pressedAtMillis: Long = 0L
    private var handledLongPress = false

    fun handle(
        event: KeyEvent,
        isLongPressKey: (Int) -> Boolean,
        onLongPress: () -> Unit
    ): Boolean {
        if (!isLongPressKey(event.keyCode)) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown(event, onLongPress)
            KeyEvent.ACTION_UP -> handleUp(event, onLongPress)
            else -> false
        }
    }

    private fun handleDown(event: KeyEvent, onLongPress: () -> Unit): Boolean {
        if (event.repeatCount == 0 || pressedKeyCode != event.keyCode) {
            pressedKeyCode = event.keyCode
            pressedAtMillis = event.eventTime
            handledLongPress = false
        }

        if (event.isLongPress || event.repeatCount > 0 || heldDurationMillis(event) >= timeoutMillis) {
            if (!handledLongPress) {
                handledLongPress = true
                onLongPress()
            }
            return true
        }

        return false
    }

    private fun handleUp(event: KeyEvent, onLongPress: () -> Unit): Boolean {
        val wasHandled = handledLongPress && pressedKeyCode == event.keyCode
        val isLongPress = !wasHandled && heldDurationMillis(event) >= timeoutMillis

        reset()

        if (isLongPress) {
            onLongPress()
            return true
        }

        return wasHandled
    }

    private fun heldDurationMillis(event: KeyEvent): Long {
        val startedAt = if (pressedKeyCode == event.keyCode) pressedAtMillis else event.downTime
        return event.eventTime - startedAt
    }

    private fun reset() {
        pressedKeyCode = null
        pressedAtMillis = 0L
        handledLongPress = false
    }
}
