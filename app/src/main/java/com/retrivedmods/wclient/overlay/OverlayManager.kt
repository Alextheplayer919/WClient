package com.retrivedmods.wclient.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.retrivedmods.wclient.game.ModuleManager
import com.retrivedmods.wclient.ui.theme.WClientTheme
import com.retrivedmods.wclient.overlay.gui.classic.OverlayButton
import com.retrivedmods.wclient.overlay.gui.classic.OverlayClickGUI
import com.retrivedmods.wclient.overlay.gui.classic.OverlayShortcutButton

import kotlinx.coroutines.launch

@SuppressLint("StaticFieldLeak")
@Suppress("MemberVisibilityCanBePrivate")
object OverlayManager {

    private val overlayWindows = ArrayList<OverlayWindow>()

    var currentContext: Context? = null
        private set

    var isShowing = false
        private set

    private var currentOverlayButton: OverlayWindow? = null
    private var currentClickGUI: OverlayWindow? = null
    private var currentClickGUIOverlay: OverlayWindow? = null

    private fun getGUITheme(context: Context): GUITheme {
        val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return try {
            GUITheme.valueOf(
                sharedPreferences.getString("gui_theme", "CLASSIC") ?: "CLASSIC"
            )
        } catch (_: Exception) {
            GUITheme.CLASSIC
        }
    }

    private fun initializeOverlays(context: Context) {
        val theme = getGUITheme(context)


        overlayWindows.clear()
        currentOverlayButton = null
        currentClickGUI = null
        currentClickGUIOverlay = null


        when (theme) {
            GUITheme.CLASSIC -> {
                currentOverlayButton = OverlayButton()
                overlayWindows.addAll(
                    ModuleManager
                        .modules
                        .filter { it.isShortcutDisplayed }
                        .map { it.overlayShortcutButton }
                )
            }
        }


        currentOverlayButton?.let { overlayWindows.add(it) }
    }

    fun showOverlayWindow(overlayWindow: OverlayWindow) {
        overlayWindows.add(overlayWindow)

        val context = currentContext
        if (isShowing && context != null) {
            showOverlayWindow(context, overlayWindow)
        }
    }

    fun dismissOverlayWindow(overlayWindow: OverlayWindow) {
        overlayWindows.remove(overlayWindow)

        val context = currentContext
        if (isShowing && context != null) {
            dismissOverlayWindow(context, overlayWindow)
        }
    }

    fun show(context: Context) {
        currentContext = context


        initializeOverlays(context)

        val theme = getGUITheme(context)
        when (theme) {

            GUITheme.CLASSIC -> {

                overlayWindows.forEach {
                    showOverlayWindow(context, it)
                }
            }
        }

        isShowing = true
    }

    fun dismiss() {
        val context = currentContext
        if (context != null) {
            val theme = getGUITheme(context)
            when (theme) {

                GUITheme.CLASSIC -> {

                    overlayWindows.forEach {
                        dismissOverlayWindow(context, it)
                    }
                }
            }
            isShowing = false
            isHudEditMode = false
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun showOverlayWindow(context: Context, overlayWindow: OverlayWindow) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutParams = overlayWindow.layoutParams
        // A HUD element added while the user is laying out the HUD must be
        // draggable straight away.
        if (isHudEditMode && overlayWindow.isHudElement) {
            layoutParams.flags =
                layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        val composeView = overlayWindow.composeView
        composeView.setContent {
            WClientTheme {
                overlayWindow.Content()
            }
        }
        val lifecycleOwner = overlayWindow.lifecycleOwner
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore
                get() = overlayWindow.viewModelStore
        })
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        composeView.compositionContext = overlayWindow.recomposer
        if (overlayWindow.firstRun) {
            overlayWindow.composeScope.launch {
                overlayWindow.recomposer.runRecomposeAndApplyChanges()
            }
            overlayWindow.firstRun = false
        }

        try {
            windowManager.addView(composeView, layoutParams)
        } catch (_: Exception) {

        }
    }

    private fun dismissOverlayWindow(context: Context, overlayWindow: OverlayWindow) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val composeView = overlayWindow.composeView

        try {
            windowManager.removeView(composeView)
        } catch (_: Exception) {

        }
    }

    // ------------------------------------------------------------------
    // HUD edit mode — drag to reposition HUD elements
    // ------------------------------------------------------------------

    /** True while the HUD Editor module is on and HUD windows accept drags. */
    var isHudEditMode by mutableStateOf(false)
        private set

    /** Snaps a dragged HUD element flush to the screen edges. */
    var hudSnapToEdges by mutableStateOf(true)

    /** Distance from a screen edge (px) that still counts as "snapped". */
    var hudSnapThresholdPx by mutableStateOf(24)

    /** Snaps dragged HUD elements to a grid; 0 disables grid snapping. */
    var hudGridSizePx by mutableStateOf(0)

    /** Draws the accent outline around every HUD element while editing. */
    var hudShowOutlines by mutableStateOf(true)

    /** Labels each outlined HUD element with its name while editing. */
    var hudShowLabels by mutableStateOf(true)

    private val hudWindows: List<OverlayWindow>
        get() = overlayWindows.filter { it.isHudElement }

    fun setHudEditMode(enabled: Boolean) {
        if (isHudEditMode == enabled) return
        isHudEditMode = enabled
        applyHudTouchFlags()
    }

    /**
     * HUD windows are `FLAG_NOT_TOUCHABLE` while playing so they never eat touches
     * meant for Minecraft. In edit mode that flag is cleared so the drag gestures
     * inside `HudEditBox` can run; it is restored on exit.
     */
    private fun applyHudTouchFlags() {
        val windowManager =
            currentContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        hudWindows.forEach { overlayWindow ->
            val layoutParams = overlayWindow.layoutParams
            layoutParams.flags = if (isHudEditMode) {
                layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try {
                windowManager.updateViewLayout(overlayWindow.composeView, layoutParams)
            } catch (_: Exception) {
            }
        }
    }

    /** Closes the in-game ClickGUI (HUD edit mode needs the screen to itself). */
    fun dismissClickGui() {
        overlayWindows.filterIsInstance<OverlayClickGUI>().forEach { dismissOverlayWindow(it) }
    }

    fun updateOverlayOpacity(opacity: Float) {
        overlayWindows.find { it is OverlayButton }?.let { button ->
            button.layoutParams.alpha = opacity
            currentContext?.let { context ->
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(button.composeView, button.layoutParams)
            }
        }
    }

    fun updateShortcutOpacity(opacity: Float) {
        overlayWindows.filter { it is OverlayShortcutButton }.forEach { button ->
            button.layoutParams.alpha = opacity
            currentContext?.let { context ->
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(button.composeView, button.layoutParams)
            }
        }
    }


}