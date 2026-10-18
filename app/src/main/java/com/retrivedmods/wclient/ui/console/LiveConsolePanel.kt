package com.retrivedmods.wclient.ui.console

import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.retrivedmods.wclient.console.ConsoleRuntime
import com.retrivedmods.wclient.overlay.OverlayManager
import com.retrivedmods.wclient.router.main.MainScreenPages
import com.retrivedmods.wclient.service.Services
import com.retrivedmods.wclient.viewmodel.MainScreenViewModel
import com.retrivedmods.wtrace.WTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional console capture next to the version/server pickers. Record/Stop is a persistent
 * master switch, separate from starting the relay or freezing the console display.
 */
@Composable
fun LiveConsolePanel(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    lines: Int = if (compact) 5 else 10
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: MainScreenViewModel = viewModel()

    val revision by WTrace.revision.collectAsState()
    val entries = remember(revision, lines) { WTrace.tail(lines) }
    val recording = ConsoleRuntime.settingsFlow.collectAsState().value.recording
    val active = recording && Services.isActive
    var expanded by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(entries.size, expanded) {
        if (expanded && entries.isNotEmpty()) {
            listState.scrollToItem(entries.size - 1)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ConsoleColors.Panel)
            .border(1.dp, if (active) ConsoleColors.Relay.copy(alpha = 0.6f) else ConsoleColors.Border, RoundedCornerShape(14.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (active) ConsoleColors.Error else ConsoleColors.Muted)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (!recording) "Console - recording off" else "Console - recording",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (!recording) {
                        "optional diagnostics disabled — no in-game console"
                    } else if (active) {
                        Services.linkSnapshot()
                            ?: "relay on: waiting for the game to connect"
                    } else {
                        "recording app diagnostics; waiting for the relay"
                    },
                    color = ConsoleColors.Muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { ConsoleRuntime.setRecording(context, !recording) }) {
                Text(if (recording) "Stop" else "Record", fontSize = 11.sp)
            }
            IconButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    val file = ConsoleRuntime.saveReport(context)
                    withContext(Dispatchers.Main) {
                        savedPath = file?.absolutePath ?: "could not write the report"
                    }
                }
            }) {
                Icon(
                    Icons.Rounded.SaveAlt,
                    contentDescription = "Save report",
                    tint = ConsoleColors.Muted,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = {
                if (!Settings.canDrawOverlays(context)) {
                    Toast.makeText(context, "Allow WClient to draw over other apps first", Toast.LENGTH_LONG).show()
                    return@IconButton
                }
                OverlayManager.toggleConsoleOverlay()
            }, enabled = recording && OverlayManager.isShowing) {
                Icon(
                    Icons.Rounded.PictureInPicture,
                    contentDescription = "Float the console over the game",
                    tint = ConsoleColors.Muted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "↑${WTrace.stats.totalToServer()} ↓${WTrace.stats.totalToClient()} pkts",
                color = ConsoleColors.Up,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "last ↑ ${ageLabel(System.currentTimeMillis(), WTrace.stats.lastToServerMs())}  " +
                    "last ↓ ${ageLabel(System.currentTimeMillis(), WTrace.stats.lastToClientMs())}",
                color = ConsoleColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "less" else "more", fontSize = 10.sp)
            }
            TextButton(onClick = { viewModel.selectPage(MainScreenPages.ConsolePage) }) {
                Text("open console", fontSize = 10.sp)
            }
        }

        if (savedPath != null) {
            Text(
                "report: $savedPath",
                color = ConsoleColors.Relay,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        val shown = if (expanded) WTrace.tail(40) else entries
        if (shown.isEmpty()) {
            Text(
                "waiting for events...",
                color = ConsoleColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        } else if (expanded) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clickable { expanded = false }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(shown, key = { it.id }) { entry ->
                    Text(
                        "${uptimeLabel(entry.uptimeMs)} ${entry.level.shortLabel()} ${entry.tag} ${entry.message}" +
                            if (entry.count > 1) "  x${entry.count}" else "",
                        color = entry.level.color(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                shown.forEach { entry ->
                    Text(
                        "${uptimeLabel(entry.uptimeMs)} ${entry.level.shortLabel()} ${entry.tag} ${entry.message}" +
                            if (entry.count > 1) "  x${entry.count}" else "",
                        color = entry.level.color(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Terminal,
                contentDescription = null,
                tint = ConsoleColors.Muted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (active) {
                    "recording since you pressed Start - tap a line for detail in the full console"
                } else {
                    "the log also holds the boot diagnostics: network interfaces, MTU, DNS, port 19132"
                },
                color = ConsoleColors.Muted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
