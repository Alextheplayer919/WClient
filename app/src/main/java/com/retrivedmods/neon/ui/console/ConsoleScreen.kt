package com.retrivedmods.neon.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.retrivedmods.neon.console.ConsoleRuntime
import com.retrivedmods.neon.console.ConsoleSettings
import com.retrivedmods.neon.service.Services
import com.retrivedmods.neon.trace.WEntry
import com.retrivedmods.neon.trace.WKind
import com.retrivedmods.neon.trace.WLevel
import com.retrivedmods.neon.trace.WTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Highest number of rows composed at once; the buffer keeps everything, the view stays smooth. */
private const val MAX_ROWS = 2_500

/**
 * The Neon console.
 *
 * Used in two places, with the same body:
 *  - as the **boot console** before the relay can be started (the app opens on it, see MainActivity),
 *  - as the **Console page** in the sidebar while the relay runs.
 *
 * It deliberately looks like a terminal rather than a log viewer: one line per event, newest at the
 * bottom, so it can be read while the game is joining.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    modifier: Modifier = Modifier,
    startup: Boolean = false,
    autoContinueSeconds: Int = 0,
    onExit: (() -> Unit)? = null,
    extraActions: @Composable RowScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val revision by WTrace.revision.collectAsState()
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<List<WEntry>>(emptyList()) }
    val live = remember(revision) { WTrace.snapshot() }
    val source = if (paused) frozen else live

    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<WKind?>(null) }
    var errorsOnly by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    val settings by ConsoleRuntime.settingsFlow.collectAsState()

    var secondsLeft by remember { mutableStateOf(autoContinueSeconds) }

    val filtered = remember(source, query, kindFilter, errorsOnly) {
        val needle = query.trim().lowercase()
        val result = ArrayList<WEntry>(minOf(source.size, MAX_ROWS))
        for (entry in source) {
            if (errorsOnly && entry.level != WLevel.ERROR && entry.level != WLevel.WARN) continue
            val kind = kindFilter
            if (kind != null && !matchesKind(entry, kind)) continue
            if (needle.isNotEmpty() &&
                !entry.message.lowercase().contains(needle) &&
                !entry.tag.lowercase().contains(needle) &&
                !(entry.detail?.lowercase()?.contains(needle) ?: false)
            ) continue
            result.add(entry)
        }
        if (result.size > MAX_ROWS) result.subList(result.size - MAX_ROWS, result.size).toList() else result
    }

    val listState = rememberLazyListState()
    LaunchedEffect(filtered.size, paused) {
        if (!paused && filtered.isNotEmpty()) {
            // Stick to the newest line, but only while the user has not scrolled away: reading history
            // must not be interrupted by a stream of packets.
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisible == -1 || lastVisible >= filtered.size - 3) {
                listState.scrollToItem(filtered.size - 1)
            }
        }
    }

    if (startup && autoContinueSeconds > 0 && onExit != null) {
        LaunchedEffect(Unit) {
            var remaining = autoContinueSeconds
            while (remaining > 0) {
                secondsLeft = remaining
                delay(1_000)
                remaining--
            }
            secondsLeft = 0
            onExit()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = ConsoleColors.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ConsoleColors.Panel,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                if (startup) "Neon boot console" else "Neon console",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (paused) {
                                    "paused - ${filtered.size} shown of ${WTrace.bufferSize()}"
                                } else {
                                    "${filtered.size} shown of ${WTrace.bufferSize()} entries" +
                                        (if (WTrace.droppedCount() > 0) " (${WTrace.droppedCount()} cycled out)" else "")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = ConsoleColors.Muted
                            )
                        }
                    }
                },
                actions = {
                    extraActions()
                    IconButton(onClick = { showStats = !showStats }) {
                        Icon(Icons.Rounded.ViewList, contentDescription = "Packet table")
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Trace settings")
                    }
                    IconButton(onClick = {
                        if (paused) {
                            paused = false
                            frozen = emptyList()
                        } else {
                            frozen = WTrace.snapshot()
                            paused = true
                        }
                    }) {
                        Icon(
                            if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = if (paused) "Resume display (recording unchanged)" else "Freeze display (recording unchanged)"
                        )
                    }
                    IconButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val file = ConsoleRuntime.saveReport(context)
                            withContext(Dispatchers.Main) {
                                savedPath = file?.absolutePath ?: "could not write the report"
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.SaveAlt, contentDescription = "Save report")
                    }
                    IconButton(onClick = {
                        WTrace.clear()
                        frozen = emptyList()
                    }) {
                        Icon(Icons.Rounded.ClearAll, contentDescription = "Clear")
                    }
                    if (onExit != null) {
                        IconButton(onClick = onExit) {
                            Icon(Icons.Rounded.Close, contentDescription = if (startup) "Continue" else "Close")
                        }
                    }
                }
            )
        },
        bottomBar = {
            ConsoleStatusBar(
                onStats = { ConsoleRuntime.dumpStatsToConsole() },
                paused = paused
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Row(
                Modifier.fillMaxWidth().background(ConsoleColors.Panel).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(if (settings.recording) "Recording ON" else "Recording OFF — no console overlay", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = { ConsoleRuntime.setRecording(context, !settings.recording) }) {
                    Text(if (settings.recording) "Stop recording" else "Start recording")
                }
            }
            if (startup) {
                StartupBanner(
                    secondsLeft = secondsLeft,
                    autoContinue = settings.autoContinueOnBoot && settings.bootHoldSeconds > 0,
                    onToggleAutoContinue = { enabled ->
                        val updated = settings.copy(autoContinueOnBoot = enabled)
                        ConsoleRuntime.updateSettings(context, updated)
                    },
                    onContinue = { onExit?.invoke() }
                )
            }

            if (savedPath != null) {
                Text(
                    "report saved: $savedPath",
                    color = ConsoleColors.Relay,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ConsoleColors.Panel)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            FilterRow(
                query = query,
                onQueryChange = { query = it },
                kindFilter = kindFilter,
                onKindChange = { kindFilter = it },
                errorsOnly = errorsOnly,
                onErrorsOnlyChange = { errorsOnly = it },
                onSaveReport = {
                    scope.launch(Dispatchers.IO) {
                        val file = ConsoleRuntime.saveReport(context)
                        withContext(Dispatchers.Main) {
                            savedPath = file?.absolutePath ?: "could not write the report"
                        }
                    }
                },
                onCopy = {
                    scope.launch(Dispatchers.IO) {
                        val text = ConsoleRuntime.reportText(context)
                        withContext(Dispatchers.Main) {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("Neon console", text)
                            )
                            savedPath = "copied to clipboard (${text.length / 1024}KiB)"
                        }
                    }
                }
            )

            if (showSettings) {
                TraceSettingsPanel(
                    settings = settings,
                    onChange = { updated ->
                        ConsoleRuntime.updateSettings(context, updated)
                    }
                )
            }

            HorizontalDivider(color = ConsoleColors.Border)

            Box(Modifier.weight(1f)) {
                if (showStats) {
                    PacketStatsPanel(Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ConsoleColors.Background)
                            .padding(horizontal = 6.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(filtered, key = { it.id }) { entry ->
                            ConsoleRow(
                                entry = entry,
                                expanded = expandedId == entry.id,
                                onToggle = { expandedId = if (expandedId == entry.id) null else entry.id }
                            )
                        }
                    }
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (source.isEmpty()) "waiting for events..." else "no entry matches the filter",
                                color = ConsoleColors.Muted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }

}

private fun matchesKind(entry: WEntry, kind: WKind): Boolean = when (kind) {
    WKind.PACKET -> entry.kind == WKind.PACKET
    WKind.NET -> entry.kind == WKind.NET
    WKind.RELAY -> entry.kind == WKind.RELAY
    WKind.CODE -> entry.kind == WKind.CODE
}

@Composable
private fun StartupBanner(
    secondsLeft: Int,
    autoContinue: Boolean,
    onToggleAutoContinue: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    Surface(color = ConsoleColors.Panel, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Everything the app and the relay do is captured here, starting before the relay is launched.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Relay start-up, RakNet handshake and MTU, every Bedrock packet with its size, " +
                        "compression/encryption switches, drops, stalls and disconnects.",
                    fontSize = 11.sp,
                    color = ConsoleColors.Muted
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoContinue, onCheckedChange = onToggleAutoContinue)
                Text(
                    if (autoContinue && secondsLeft > 0) "auto (${secondsLeft}s)" else "auto",
                    fontSize = 11.sp,
                    color = ConsoleColors.Muted
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onContinue) {
                Text(if (Services.isActive) "Open Neon" else "Continue")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    query: String,
    onQueryChange: (String) -> Unit,
    kindFilter: WKind?,
    onKindChange: (WKind?) -> Unit,
    errorsOnly: Boolean,
    onErrorsOnlyChange: (Boolean) -> Unit,
    onSaveReport: () -> Unit,
    onCopy: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ConsoleColors.Panel)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text("filter: packet name, tag or text - e.g. LevelChunk, watchdog, MTU", fontSize = 11.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = kindFilter == null,
                onClick = { onKindChange(null) },
                label = { Text("all", fontSize = 11.sp) }
            )
            FilterChip(
                selected = kindFilter == WKind.PACKET,
                onClick = { onKindChange(WKind.PACKET) },
                label = { Text("packets", fontSize = 11.sp) }
            )
            FilterChip(
                selected = kindFilter == WKind.NET,
                onClick = { onKindChange(WKind.NET) },
                label = { Text("network", fontSize = 11.sp) }
            )
            FilterChip(
                selected = kindFilter == WKind.RELAY,
                onClick = { onKindChange(WKind.RELAY) },
                label = { Text("relay", fontSize = 11.sp) }
            )
            FilterChip(
                selected = kindFilter == WKind.CODE,
                onClick = { onKindChange(WKind.CODE) },
                label = { Text("code", fontSize = 11.sp) }
            )
            FilterChip(
                selected = errorsOnly,
                onClick = { onErrorsOnlyChange(!errorsOnly) },
                label = { Text("issues", fontSize = 11.sp) }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSaveReport) { Text("save report", fontSize = 11.sp) }
            TextButton(onClick = onCopy) { Text("copy all", fontSize = 11.sp) }
            TextButton(onClick = { ConsoleRuntime.dumpStatsToConsole() }) { Text("packet table", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ConsoleRow(entry: WEntry, expanded: Boolean, onToggle: () -> Unit) {
    val hasDetail = entry.detail != null
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = hasDetail, onClick = onToggle)
            .padding(vertical = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                uptimeLabel(entry.uptimeMs),
                color = ConsoleColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.width(64.dp)
            )
            Text(
                entry.level.shortLabel(),
                color = entry.level.color(),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.width(30.dp)
            )
            Text(
                entry.kind.label(),
                color = entry.kind.color(),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.width(48.dp)
            )
            Text(
                entry.tag,
                color = ConsoleColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(150.dp)
            )
            Text(
                text = buildString {
                    append(entry.message)
                    if (entry.count > 1) append("   x").append(entry.count)
                    if (hasDetail) append(if (expanded) "   [tap to collapse]" else "   [tap for detail]")
                },
                color = entry.level.color(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded && entry.detail != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 64.dp)
                    .background(ConsoleColors.RowAlt, RoundedCornerShape(3.dp))
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 320.dp)
            ) {
                Text(
                    entry.detail!!,
                    color = ConsoleColors.Debug,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(6.dp)
                )
            }
        }
    }
}

@Composable
private fun ConsoleStatusBar(paused: Boolean, onStats: () -> Unit) {
    val revision by WTrace.revision.collectAsState()
    val now = remember(revision) { System.currentTimeMillis() }

    Surface(color = ConsoleColors.Panel, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "↑ ${WTrace.stats.totalToServer()} (${bytesLabel(WTrace.stats.totalBytesToServer())})",
                    color = ConsoleColors.Up,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "↓ ${WTrace.stats.totalToClient()} (${bytesLabel(WTrace.stats.totalBytesToClient())})",
                    color = ConsoleColors.Down,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "last ↑ ${ageLabel(now, WTrace.stats.lastToServerMs())}  last ↓ ${ageLabel(now, WTrace.stats.lastToClientMs())}",
                    color = ConsoleColors.Muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (paused) "PAUSED" else "LIVE",
                    color = if (paused) ConsoleColors.Warn else ConsoleColors.Relay,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.clickable(onClick = onStats)
                )
            }
            Text(
                Services.linkSnapshot() ?: "relay idle - press Start on the Home page to capture packets",
                color = ConsoleColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PacketStatsPanel(modifier: Modifier = Modifier) {
    val revision by WTrace.revision.collectAsState()
    val stats = remember(revision) { WTrace.stats.snapshot() }
    val now = remember(revision) { System.currentTimeMillis() }

    Column(
        modifier
            .background(ConsoleColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(
            "per packet type - ↑ = game→server, ↓ = server→game (count, bytes, last seen)",
            color = ConsoleColors.Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(6.dp))
        if (stats.isEmpty()) {
            Text(
                "no packets recorded yet - start the relay and join a server",
                color = ConsoleColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            return@Column
        }
        val toClient = stats.filter { it.direction == com.retrivedmods.neon.trace.WPacketStats.TO_CLIENT }
        val toServer = stats.filter { it.direction != com.retrivedmods.neon.trace.WPacketStats.TO_CLIENT }
        StatsSection("server → game (what the device receives)", toClient, now, ConsoleColors.Down)
        Spacer(Modifier.height(10.dp))
        StatsSection("game → server (what the device sends)", toServer, now, ConsoleColors.Up)
    }
}

@Composable
private fun StatsSection(title: String, rows: List<com.retrivedmods.neon.trace.WPacketStat>, now: Long, tint: Color) {
    Text(title, color = tint, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
    rows.forEach { stat ->
        Row(
            Modifier
                .fillMaxWidth()
                .border(0.5.dp, ConsoleColors.Border)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                stat.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${stat.count}  ${bytesLabel(stat.bytes)}  avg ${bytesLabel(stat.averageBytes.toLong())}",
                color = ConsoleColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                ageLabel(now, stat.lastSeenMs),
                color = if (now - stat.lastSeenMs > 8_000) ConsoleColors.Warn else ConsoleColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraceSettingsPanel(settings: ConsoleSettings, onChange: (ConsoleSettings) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ConsoleColors.Panel)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("trace depth", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        SettingToggle("record console (enables capture and console overlays)", settings.recording) { onChange(settings.copy(recording = it)) }
        SettingToggle("console on boot", settings.showOnBoot) { onChange(settings.copy(showOnBoot = it)) }
        SettingToggle("every Bedrock packet (rate limited per type)", settings.packetTrace) { onChange(settings.copy(packetTrace = it)) }
        SettingToggle("RakNet events: handshake, MTU, splits, acks, resends", settings.netTrace) { onChange(settings.copy(netTrace = it)) }
        SettingToggle("every datagram (very noisy)", settings.datagramTrace) { onChange(settings.copy(datagramTrace = it)) }
        SettingToggle("app logs (android.util.Log / println)", settings.codeTrace) { onChange(settings.copy(codeTrace = it)) }
        SettingToggle("floating console button over the game", settings.overlayButton) { onChange(settings.copy(overlayButton = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("minimum level", color = ConsoleColors.Muted, fontSize = 11.sp, modifier = Modifier.width(120.dp))
            WLevel.entries.forEach { level ->
                FilterChip(
                    selected = settings.minLevel == level,
                    onClick = { onChange(settings.copy(minLevel = level)) },
                    label = { Text(level.shortLabel(), fontSize = 10.sp) },
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
        Text(
            "buffer keeps ${settings.capacity} lines (~${settings.capacity * 220 / 1024 / 1024}MB heap when full)",
            color = ConsoleColors.Muted,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
    }
}

/**
 * Compact live view used by the in-game floating console: no scaffold, no filters, just the tail of
 * the buffer and the counters, so it fits in a corner of the screen while playing.
 */
@Composable
fun ConsoleMiniView(
    modifier: Modifier = Modifier,
    maxRows: Int = 40,
    onSave: () -> Unit = {},
    onClose: (() -> Unit)? = null
) {
    val revision by WTrace.revision.collectAsState()
    val entries = remember(revision) { WTrace.tail(maxRows) }
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }

    Column(
        modifier
            .background(ConsoleColors.Background, RoundedCornerShape(6.dp))
            .border(1.dp, ConsoleColors.Border, RoundedCornerShape(6.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(ConsoleColors.Panel)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "console  ↑${WTrace.stats.totalToServer()} ↓${WTrace.stats.totalToClient()}",
                color = ConsoleColors.Relay,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSave) { Text("save", fontSize = 9.sp) }
            if (onClose != null) {
                TextButton(onClick = onClose) { Text("x", fontSize = 9.sp) }
            }
        }
        if (entries.isEmpty()) {
            Text(
                "waiting for events...",
                color = ConsoleColors.Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(6.dp)
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 240.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Text(
                        "${uptimeLabel(entry.uptimeMs)} ${entry.level.shortLabel()} ${entry.tag} ${entry.message}" +
                            if (entry.count > 1) "  x${entry.count}" else "",
                        color = entry.level.color(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}
