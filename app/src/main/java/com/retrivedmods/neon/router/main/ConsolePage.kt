package com.retrivedmods.neon.router.main

import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.retrivedmods.neon.console.ConsoleRuntime
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.retrivedmods.neon.overlay.OverlayManager
import com.retrivedmods.neon.ui.console.ConsoleScreen

/**
 * The console as a sidebar page, live while the relay runs.
 *
 * Its extra action moves the same view over the game as a floating window (available once the app is
 * allowed to draw over other apps, which the Home page asks for).
 */
@Composable
fun ConsolePageContent() {
    val context = LocalContext.current
    val recording = ConsoleRuntime.settingsFlow.collectAsState().value.recording

    ConsoleScreen(
        modifier = Modifier.fillMaxSize(),
        extraActions = {
            IconButton(onClick = {
                if (!Settings.canDrawOverlays(context)) {
                    Toast.makeText(
                        context,
                        "Allow Neon to draw over other apps first (Home page asks for it)",
                        Toast.LENGTH_LONG
                    ).show()
                    return@IconButton
                }
                OverlayManager.toggleConsoleOverlay()
            }, enabled = recording) {
                Icon(Icons.Rounded.PictureInPicture, contentDescription = "Float over the game")
            }
        }
    )
}
