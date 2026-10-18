package com.retrivedmods.neon.overlay.console

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.retrivedmods.neon.R
import com.retrivedmods.neon.console.ConsoleRuntime
import com.retrivedmods.neon.overlay.OverlayManager
import com.retrivedmods.neon.overlay.OverlayWindow
import com.retrivedmods.neon.ui.console.ConsoleMiniView

/**
 * Floating console button shown over the game while the relay runs.
 *
 * Same pattern as the existing overlay button: draggable, not focusable (so it never steals a touch
 * from the game), and it opens [ConsoleOverlayWindow] on tap.
 */
class ConsoleOverlayButton : OverlayWindow() {

    private val _layoutParams by lazy {
        super.layoutParams.apply {
            gravity = Gravity.TOP or Gravity.END
            windowAnimations = android.R.style.Animation_Toast
            x = 16
            y = 220
        }
    }

    override val layoutParams: WindowManager.LayoutParams
        get() = _layoutParams


    @Composable
    override fun Content() {
        val context = LocalContext.current
        ElevatedCard(
            onClick = { OverlayManager.toggleConsoleOverlay() },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .padding(5.dp)
                .pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        _layoutParams.x -= drag.x.toInt()
                        _layoutParams.y += drag.y.toInt()
                        windowManager.updateViewLayout(composeView, _layoutParams)
                    }
                }
        ) {
            Image(
                painter = painterResource(R.drawable.neon_launcher),
                contentDescription = "Open console",
                modifier = Modifier
                    .padding(0.dp)
                    .size(38.dp)
            )
        }
    }
}

/**
 * The floating console itself: the last 40 lines and the packet counters, live, over the game.
 */
class ConsoleOverlayWindow : OverlayWindow() {

    private val _layoutParams by lazy {
        super.layoutParams.apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = android.R.style.Animation_Toast
            x = 40
            y = 120
            alpha = 0.92f
        }
    }

    override val layoutParams: WindowManager.LayoutParams
        get() = _layoutParams

    @Composable
    override fun Content() {
        val context = LocalContext.current
        ConsoleMiniView(
            modifier = Modifier
                .width(430.dp)
                .pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        _layoutParams.x += drag.x.toInt()
                        _layoutParams.y += drag.y.toInt()
                        windowManager.updateViewLayout(composeView, _layoutParams)
                    }
                },
            onSave = { ConsoleRuntime.saveReport(context) },
            onClose = { OverlayManager.dismissOverlayWindow(this) }
        )
    }
}
