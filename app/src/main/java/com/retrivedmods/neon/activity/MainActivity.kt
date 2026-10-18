package com.retrivedmods.neon.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.retrivedmods.neon.console.ConsoleRuntime
import com.retrivedmods.neon.game.ModuleManager
import com.retrivedmods.neon.navigation.Navigation
import com.retrivedmods.neon.ui.component.LoadingScreen
import com.retrivedmods.neon.ui.console.ConsoleScreen
import com.retrivedmods.neon.ui.theme.NeonTheme
import com.retrivedmods.neon.util.SoundUtil

class MainActivity : ComponentActivity() {

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(
                this,
                "Storage permissions granted - You can now export configs",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                "Storage permissions are required to export configs to external storage",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoundUtil.load(applicationContext)

        ModuleManager.loadConfig()

        enableEdgeToEdge()
        setupImmersiveMode()
        checkBatteryOptimizations()

        requestStoragePermissions()

        setContent {
            NeonTheme {
                // Boot order: console first (it captures everything from before the relay can be
                // started), then the existing loading screen, then the app itself.
                var stage by remember {
                    mutableStateOf(if (ConsoleRuntime.settings.recording && ConsoleRuntime.settings.showOnBoot) BootStage.Console else BootStage.Loading)
                }

                when (stage) {
                    BootStage.Console -> ConsoleScreen(
                        startup = true,
                        autoContinueSeconds = if (ConsoleRuntime.settings.autoContinueOnBoot) {
                            ConsoleRuntime.settings.bootHoldSeconds
                        } else {
                            0
                        },
                        onExit = { stage = BootStage.Loading }
                    )

                    BootStage.Loading -> LoadingScreen(
                        onDone = {
                            stage = BootStage.Main
                        }
                    )

                    BootStage.Main -> Navigation()
                }
            }
        }
    }

    private enum class BootStage {
        Console,
        Loading,
        Main
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimizations() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
            )
        }
    }

    private fun requestStoragePermissions() {
        if (hasStoragePermissions()) {
            return
        }

        val permissions = getRequiredStoragePermissions()
        storagePermissionLauncher.launch(permissions)
    }

    private fun hasStoragePermissions(): Boolean {
        val permissions = getRequiredStoragePermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredStoragePermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            else -> {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ModuleManager.saveConfig()
    }
}