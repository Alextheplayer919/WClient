package com.retrivedmods.neon.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.retrivedmods.neon.console.ConsoleRuntime
import com.retrivedmods.neon.game.ModuleManager
import com.retrivedmods.neon.ui.theme.NeonTheme
import com.retrivedmods.neon.overlay.gui.classic.OverlayButton
import com.retrivedmods.neon.overlay.gui.classic.OverlayShortcutButton

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

    /** Floating console button over the game, when the console's overlay switch is on. */
    private var consoleOverlayButton: OverlayWindow? = null

    private fun initializeOverlays(context: Context) {
        val theme = getGUITheme(context)


        overlayWindows.clear()
        currentOverlayButton = null
        currentClickGUI = null
        currentClickGUIOverlay = null
        consoleOverlayButton = null
        consoleOverlayWindow = null


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

        // The console button rides along with the relay's overlay: it is how the user watches packets
        // while the game is in the foreground, which is exactly when a freeze happens.
        if (ConsoleRuntime.settings.recording && ConsoleRuntime.settings.overlayButton) {
            val button = com.retrivedmods.neon.overlay.console.ConsoleOverlayButton()
            consoleOverlayButton = button
            overlayWindows.add(button)
        }
    }

    /** Opens or closes the floating console window, e.g. from the console page. */
    fun toggleConsoleOverlay() {
        if (!isShowing || !ConsoleRuntime.settings.recording) return
        val context = currentContext ?: return
        val open = consoleOverlayWindow
        if (open != null) {
            overlayWindows.remove(open)
            dismissOverlayWindow(context, open)
            consoleOverlayWindow = null
            return
        }
        val window = com.retrivedmods.neon.overlay.console.ConsoleOverlayWindow()
        consoleOverlayWindow = window
        overlayWindows.add(window)
        showOverlayWindow(context, window)
    }

    /** Called on the main thread after a recording/overlay preference changes. */
    fun refreshConsoleOverlays() {
        if (!ConsoleRuntime.settings.recording) {
            consoleOverlayWindow?.let { dismissOverlayWindow(it) }
        }
        val showButton = isShowing && ConsoleRuntime.settings.recording && ConsoleRuntime.settings.overlayButton
        if (!showButton) {
            consoleOverlayButton?.let { dismissOverlayWindow(it) }
        } else if (consoleOverlayButton == null) {
            val button = com.retrivedmods.neon.overlay.console.ConsoleOverlayButton()
            consoleOverlayButton = button
            showOverlayWindow(button)
        }
    }

    private var consoleOverlayWindow: OverlayWindow? = null

    fun showOverlayWindow(overlayWindow: OverlayWindow) {
        if (overlayWindows.contains(overlayWindow)) return
        overlayWindows.add(overlayWindow)

        val context = currentContext
        if (isShowing && context != null) {
            showOverlayWindow(context, overlayWindow)
        }
    }

    fun dismissOverlayWindow(overlayWindow: OverlayWindow) {
        overlayWindows.remove(overlayWindow)
        if (consoleOverlayWindow === overlayWindow) consoleOverlayWindow = null
        if (consoleOverlayButton === overlayWindow) consoleOverlayButton = null

        val context = currentContext
        if (isShowing && context != null) {
            dismissOverlayWindow(context, overlayWindow)
        }
    }

    fun show(context: Context) {
        if (isShowing) dismiss()
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
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun showOverlayWindow(context: Context, overlayWindow: OverlayWindow) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutParams = overlayWindow.layoutParams
        val composeView = overlayWindow.composeView
        composeView.setContent {
            NeonTheme {
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