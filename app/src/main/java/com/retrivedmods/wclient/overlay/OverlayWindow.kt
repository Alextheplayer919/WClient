package com.retrivedmods.wclient.overlay

import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope

@Suppress("MemberVisibilityCanBePrivate")
abstract class OverlayWindow {

    /**
     * Whether this window should be focusable. Focusable windows receive input
     * events and can host editable text fields (IME / soft keyboard). Floating
     * action buttons keep this false so they don't steal touch/keys from the
     * underlying game.
     */
    open val focusable: Boolean = false

    open val layoutParams by lazy {
        LayoutParams().apply {
            width = LayoutParams.WRAP_CONTENT
            height = LayoutParams.WRAP_CONTENT
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
            type = LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // FLAG_NOT_FOCUSABLE is required by default so the game behind the
            // overlay keeps receiving its touch/key events. Focusable windows
            // (e.g. the ClickGUI) clear this flag so text fields can pull up
            // the keyboard.
            flags = if (focusable) {
                // Allow the window to receive key/touch events so text fields
                // can receive focus and show the soft keyboard.
                LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                // Non-focusable floating widgets (overlay button, shortcut
                // buttons) must not steal events from the underlying game.
                LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            // State hidden by default; tapping an OutlinedTextField brings
            // the keyboard up. Don't resize the overlay when the IME shows.
            softInputMode = LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                LayoutParams.SOFT_INPUT_STATE_HIDDEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alpha =
                    (OverlayManager.currentContext?.getSystemService(Service.INPUT_SERVICE) as? InputManager)?.maximumObscuringOpacityForTouch
                        ?: 0.8f
            }
        }
    }

    open val composeView by lazy {
        ComposeView(OverlayManager.currentContext!!)
    }

    val windowManager: WindowManager
        get() = OverlayManager.currentContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val lifecycleOwner = OverlayLifecycleOwner()

    val viewModelStore = ViewModelStore()

    val composeScope: CoroutineScope

    val recomposer: Recomposer

    var firstRun = true

    init {
        lifecycleOwner.performRestore(null)

        val coroutineContext = AndroidUiDispatcher.CurrentThread
        composeScope = CoroutineScope(coroutineContext)
        recomposer = Recomposer(coroutineContext)
    }

    @Composable
    abstract fun Content()

}
