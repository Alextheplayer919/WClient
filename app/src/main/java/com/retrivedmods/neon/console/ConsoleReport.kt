package com.retrivedmods.neon.console

import android.content.Context
import android.os.Build
import android.os.Environment
import com.retrivedmods.neon.model.CaptureModeModel
import com.retrivedmods.neon.trace.WTrace
import com.retrivedmods.neon.trace.WTraceReport
import java.io.File

/**
 * Saves the console buffer as a text file the user can hand over or attach to a report.
 *
 * Location follows the same rule as the config exports: the public `Neon/console` folder when it is
 * writable, otherwise the app-specific external folder, otherwise internal storage.
 */
object ConsoleReport {

    fun reportDirectory(context: Context): File {
        val publicDir = File(Environment.getExternalStorageDirectory(), "Neon/console")
        if (publicDir.exists() || publicDir.mkdirs()) {
            val probe = File(publicDir, ".neon_probe")
            if (runCatching { probe.writeText("ok"); probe.delete() }.getOrDefault(false)) {
                return publicDir
            }
        }
        context.getExternalFilesDir("console")?.let { external ->
            if (external.exists() || external.mkdirs()) {
                return external
            }
        }
        return context.filesDir.resolve("console").also { it.mkdirs() }
    }

    fun buildText(context: Context): String {
        val target = runCatching {
            CaptureModeModel.from(context.getSharedPreferences("game_settings", Context.MODE_PRIVATE))
        }.getOrNull()

        val header = buildString {
            appendLine("Neon console report")
            appendLine("app: v${ConsoleRuntime.packageVersion(context)} (${context.packageName})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("process heap max: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB")
            if (target != null) {
                appendLine("configured target: ${target.serverHostName}:${target.serverPort} (protected=${target.isProtectedServer()})")
            }
            if (com.retrivedmods.neon.service.Services.isActive) {
                appendLine("relay: running (${com.retrivedmods.neon.service.Services.linkSnapshot() ?: "no session"})")
            } else {
                appendLine("relay: not running")
            }
        }

        return WTraceReport.build(
            header = header,
            filterDescription = "full buffer (${WTrace.bufferSize()} entries)"
        )
    }

    fun save(context: Context): File? = runCatching {
        val directory = reportDirectory(context)
        val file = File(directory, WTraceReport.suggestedFileName())
        file.writeText(buildText(context))
        WTrace.info("console", "report saved: ${file.absolutePath} (${file.length() / 1024}KiB)")
        file
    }.onFailure {
        WTrace.error("console", "could not save the report: ${it.message}")
    }.getOrNull()
}
