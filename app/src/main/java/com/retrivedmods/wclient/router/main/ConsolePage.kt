package com.retrivedmods.wclient.router.main

import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.retrivedmods.wclient.overlay.OverlayManager
import com.retrivedmods.wclient.ui.console.ConsoleScreen

/**
 * The console as a sidebar page, live while the relay runs.
 *
 * Its extra action moves the same view over the game as a floating window (available once the app is
 * allowed to draw over other apps, which the Home page asks for).
 */
@Composable
fun ConsolePageContent() {
    val context = LocalContext.current

    ConsoleScreen(
        modifier = Modifier.fillMaxSize(),
        extraActions = {
            IconButton(onClick = {
                if (!Settings.canDrawOverlays(context)) {
                    Toast.makeText(
                        context,
                        "Allow WClient to draw over other apps first (Home page asks for it)",
                        Toast.LENGTH_LONG
                    ).show()
                    return@IconButton
                }
                OverlayManager.toggleConsoleOverlay()
            }) {
                Icon(Icons.Rounded.PictureInPicture, contentDescription = "Float over the game")
            }
        }
    )
}
