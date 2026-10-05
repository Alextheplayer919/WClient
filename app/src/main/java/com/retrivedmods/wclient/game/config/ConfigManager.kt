package com.retrivedmods.wclient.game.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import com.retrivedmods.wclient.application.AppContext
import com.retrivedmods.wclient.game.ModuleManager
import java.io.File

/**
 * A named, user-created config profile stored as a standalone JSON file
 * inside WClient's own directory.
 */
data class ConfigProfile(
    val name: String,
    val file: File,
    val lastModified: Long
)

/**
 * Profile-based config storage.
 *
 * WClient owns its own directory ("/storage/emulated/0/WClient/configs" when
 * all-files access is available, otherwise the app-specific external dir) and
 * any number of named profiles can be saved / loaded / deleted at runtime —
 * including from the in-game ClickGUI, at any time.
 *
 * This is separate from [ModuleManager.saveConfig] / [ModuleManager.loadConfig],
 * which keep acting as the automatic "last session state" persistence.
 */
object ConfigManager {

    private const val PREFS_NAME = "wclient_configs"
    private const val KEY_ACTIVE_PROFILE = "active_profile"

    /** Name of the profile that was last saved or loaded, for UI display. */
    val activeProfileName = mutableStateOf<String?>(null)

    private var activeProfileLoaded = false

    private fun prefs() =
        if (AppContext.isInitialized) {
            AppContext.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } else null

    private fun ensureActiveProfileLoaded() {
        if (!activeProfileLoaded) {
            activeProfileLoaded = true
            activeProfileName.value = prefs()?.getString(KEY_ACTIVE_PROFILE, null)
        }
    }

    private fun setActiveProfile(name: String?) {
        ensureActiveProfileLoaded()
        activeProfileName.value = name
        prefs()?.edit()?.apply {
            if (name == null) remove(KEY_ACTIVE_PROFILE) else putString(KEY_ACTIVE_PROFILE, name)
            apply()
        }
    }

    // ------------------------------------------------------------------
    // Storage resolution
    // ------------------------------------------------------------------

    /** True when we can write anywhere on shared storage (Android 11+ "All files access"). */
    val hasAllFilesAccess: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // API 28/29: WRITE_EXTERNAL_STORAGE from the manifest is enough
        }

    /** Opens the system screen where the user can grant "All files access". */
    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = try {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } catch (e: Exception) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * WClient's own configs directory, best candidate first:
     *  1. /storage/emulated/0/WClient/configs   (visible to any file manager)
     *  2. Android/data/<pkg>/files/configs      (no permission required)
     *  3. internal filesDir/configs             (always works)
     */
    val configsDirectory: File
        get() {
            publicConfigsDirectory()?.let { return it }
            if (AppContext.isInitialized) {
                AppContext.instance.getExternalFilesDir("configs")?.let {
                    if (it.exists() || it.mkdirs()) return it
                }
                return AppContext.instance.filesDir.resolve("configs").also { it.mkdirs() }
            }
            return File(Environment.getExternalStorageDirectory(), "WClient/configs")
        }

    /** The public WClient dir, or null when it is not actually writable. */
    private fun publicConfigsDirectory(): File? {
        return try {
            val dir = File(Environment.getExternalStorageDirectory(), "WClient/configs")
            if (!dir.exists() && !dir.mkdirs()) return null
            // Probe real writability — mkdirs() can lie under scoped storage.
            val probe = File(dir, ".wclient_probe")
            probe.writeText("ok")
            probe.delete()
            dir
        } catch (e: Exception) {
            null
        }
    }

    /** True when profiles are stored in the public /WClient directory. */
    val isUsingPublicDirectory: Boolean
        get() = try {
            configsDirectory.absolutePath.startsWith(
                File(Environment.getExternalStorageDirectory(), "WClient").absolutePath
            )
        } catch (e: Exception) {
            false
        }

    // ------------------------------------------------------------------
    // Profiles
    // ------------------------------------------------------------------

    fun sanitizeName(raw: String): String =
        raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
            .removeSuffix(".json")
            .take(64)

    fun listProfiles(): List<ConfigProfile> {
        ensureActiveProfileLoaded()
        return try {
            configsDirectory.listFiles { file ->
                file.isFile && file.extension.equals("json", ignoreCase = true) &&
                        !file.name.endsWith(".tmp")
            }?.map {
                ConfigProfile(it.nameWithoutExtension, it, it.lastModified())
            }?.sortedByDescending { it.lastModified } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Saves the current module state as a named profile. Atomic: writes to a
     * temp file first, then renames over the target, so a crash mid-save can
     * never corrupt an existing profile.
     */
    fun saveProfile(rawName: String): Result<ConfigProfile> {
        val name = sanitizeName(rawName)
        if (name.isEmpty()) {
            return Result.failure(IllegalArgumentException("Config name cannot be empty"))
        }
        return try {
            val dir = configsDirectory
            dir.mkdirs()
            val target = File(dir, "$name.json")
            val tmp = File(dir, "$name.json.tmp")

            tmp.writeText(ModuleManager.exportConfig())
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                // Rename across weird mounts can fail — fall back to copy.
                target.writeText(tmp.readText())
                tmp.delete()
            }

            setActiveProfile(name)
            // Keep the automatic session config in sync as well.
            ModuleManager.saveConfig()
            Result.success(ConfigProfile(name, target, target.lastModified()))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /** Applies a saved profile to all modules. */
    fun loadProfile(name: String): Result<Unit> {
        return try {
            val file = File(configsDirectory, "${sanitizeName(name)}.json")
            if (!file.exists()) {
                return Result.failure(IllegalArgumentException("Config \"$name\" not found"))
            }
            ModuleManager.importConfig(file.readText())
            setActiveProfile(sanitizeName(name))
            // Persist as the session state so it survives an app restart.
            ModuleManager.saveConfig()
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    fun deleteProfile(name: String): Boolean {
        return try {
            val file = File(configsDirectory, "${sanitizeName(name)}.json")
            val deleted = file.exists() && file.delete()
            if (deleted && activeProfileName.value == sanitizeName(name)) {
                setActiveProfile(null)
            }
            deleted
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
