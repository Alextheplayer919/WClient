package com.retrivedmods.wclient.overlay.gui.classic

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrivedmods.wclient.game.ModuleManager
import com.retrivedmods.wclient.game.config.ConfigManager
import com.retrivedmods.wclient.game.config.ConfigProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val CardBackgroundExpanded = Color(0xFF1A1212)
private val AccentPrimary = Color(0xFFE63946)
private val TextPrimary = Color(0xFFE8E8E8)
private val TextSecondary = Color(0xFFB0B0B0)
private val TextTertiary = Color(0xFF888888)
private val ButtonBackground = Color(0xFF251A1A)
private val FieldBackground = Color(0xFF140E0E)

@Composable
fun ConfigurationScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onToneChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var profileName by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf(ConfigManager.listProfiles()) }
    val activeProfile by ConfigManager.activeProfileName
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        profiles = ConfigManager.listProfiles()
    }

    /**
     * Shows the in-GUI status popup. [error] only drives the text colour — the
     * messages carry no emoji / icon prefixes, so the popup matches the rest of
     * the client.
     */
    fun notify(message: String, error: Boolean = false) {
        onToneChange(error)
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun saveProfile(name: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val result = ConfigManager.saveProfile(name)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    profileName = ""
                    refresh()
                    notify("Saved config \"${it.name}\"")
                }.onFailure {
                    notify(it.message ?: "Failed to save config", error = true)
                }
            }
        }
    }

    fun loadProfile(name: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val result = ConfigManager.loadProfile(name)
            withContext(Dispatchers.Main) {
                result.onSuccess { notify("Loaded config \"$name\"") }
                    .onFailure { notify(it.message ?: "Failed to load config", error = true) }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            if (ModuleManager.importConfigFromFile(context, it)) {
                notify("Config imported — type a name and save to keep it")
            } else {
                notify("Failed to import config", error = true)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ConfigurationHeader(
                activeProfile = activeProfile,
                storagePath = remember { ConfigManager.configsDirectory.absolutePath }
            )
        }

        if (!ConfigManager.hasAllFilesAccess && !ConfigManager.isUsingPublicDirectory) {
            item {
                StorageAccessCard(onGrantClick = { ConfigManager.requestAllFilesAccess(context) })
            }
        }

        item {
            SaveProfileRow(
                profileName = profileName,
                onNameChange = { profileName = it },
                onSaveClick = {
                    if (ConfigManager.sanitizeName(profileName).isEmpty()) {
                        notify("Enter a config name first", error = true)
                    } else {
                        saveProfile(profileName)
                    }
                }
            )
        }

        if (profiles.isEmpty()) {
            item {
                Text(
                    text = "No saved configs yet — type a name above and hit Save.",
                    color = TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(profiles, key = { it.file.absolutePath }) { profile ->
                ProfileRow(
                    profile = profile,
                    isActive = profile.name == activeProfile,
                    isPendingDelete = pendingDelete == profile.name,
                    onLoad = { loadProfile(profile.name) },
                    onOverwrite = { saveProfile(profile.name) },
                    onDelete = {
                        if (pendingDelete == profile.name) {
                            pendingDelete = null
                            if (ConfigManager.deleteProfile(profile.name)) {
                                refresh()
                                notify("Deleted \"${profile.name}\"")
                            } else {
                                notify("Failed to delete \"${profile.name}\"", error = true)
                            }
                        } else {
                            pendingDelete = profile.name
                            notify("Tap delete again to remove \"${profile.name}\"")
                        }
                    }
                )
            }
        }

        item {
            ImportExportRow(
                onImportClick = { filePickerLauncher.launch("application/json") },
                onExportClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        val fileName = generateUniqueFileName()
                        val success = exportConfigToWClientFolder(fileName)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                refresh()
                                val path = File(ConfigManager.configsDirectory, fileName).absolutePath
                                notify("Exported to: $path")
                            } else {
                                notify("Failed to export config", error = true)
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ConfigurationHeader(
    activeProfile: String?,
    storagePath: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Configs",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (activeProfile != null) {
                Surface(
                    color = ButtonBackground,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = activeProfile,
                        color = AccentPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
        Text(
            text = storagePath,
            color = TextTertiary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StorageAccessCard(onGrantClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.elevatedCardElevation(0.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = CardBackgroundExpanded)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Grant \"All files access\" to store configs in /WClient where any file manager can see them.",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onGrantClick) {
                Text("Grant", color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveProfileRow(
    profileName: String,
    onNameChange: (String) -> Unit,
    onSaveClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = profileName,
            onValueChange = onNameChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Config name…", color = TextTertiary, fontSize = 13.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentPrimary,
                unfocusedBorderColor = Color(0xFF3A2A2A),
                cursorColor = AccentPrimary,
                focusedContainerColor = FieldBackground,
                unfocusedContainerColor = FieldBackground
            )
        )
        Button(
            onClick = onSaveClick,
            modifier = Modifier.height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ConfigProfile,
    isActive: Boolean,
    isPendingDelete: Boolean,
    onLoad: () -> Unit,
    onOverwrite: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.elevatedCardElevation(0.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isActive) Color(0xFF241416) else CardBackgroundExpanded
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    color = if (isActive) AccentPrimary else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatDate(profile.lastModified),
                    color = TextTertiary,
                    fontSize = 10.sp
                )
            }

            TextButton(onClick = onLoad, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("Load", color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onOverwrite, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Save,
                    contentDescription = "Overwrite with current settings",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Delete",
                    tint = if (isPendingDelete) AccentPrimary else TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ImportExportRow(
    onImportClick: () -> Unit,
    onExportClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onImportClick,
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = ButtonBackground,
                contentColor = TextPrimary
            ),
            border = null,
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Import", fontSize = 13.sp)
        }
        OutlinedButton(
            onClick = onExportClick,
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = ButtonBackground,
                contentColor = TextPrimary
            ),
            border = null,
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentPrimary)
            Spacer(Modifier.width(8.dp))
            Text("Export", fontSize = 13.sp)
        }
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun generateUniqueFileName(): String {
    val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    val timestamp = dateFormat.format(Date())
    return "export_$timestamp.json"
}

private fun exportConfigToWClientFolder(fileName: String): Boolean {
    return try {
        val configsDir = ConfigManager.configsDirectory
        configsDir.mkdirs()
        val file = File(configsDir, fileName)
        file.writeText(ModuleManager.exportConfig())
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
