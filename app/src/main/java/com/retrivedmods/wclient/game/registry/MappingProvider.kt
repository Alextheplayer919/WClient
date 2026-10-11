package com.retrivedmods.wclient.game.registry

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser

abstract class MappingProvider<T>(val context: Context) {

    protected abstract val assetPath: String

    protected open val availableVersions: Array<Short> by lazy {
        val indexPath = "$assetPath/index.json"
        try {
            val stream = context.assets.open(indexPath)
            val json = JsonParser.parseReader(stream.reader(Charsets.UTF_8)).asJsonArray
            val versions = json.map { it.asShort }.sortedBy { it }.toTypedArray()
            Log.i("MappingProvider", "Loaded available versions from $indexPath: ${versions.joinToString()}")
            versions
        } catch (e: Exception) {
            Log.e("MappingProvider", "Failed to load available versions from $indexPath", e)
            throw e
        }
    }

    open fun craftMapping(protocolVersion: Int): T {
        val available = availableVersions.filter { it <= protocolVersion }
        if (available.isEmpty()) {
            Log.w("MappingProvider", "No available mapping version <= $protocolVersion in $assetPath")
            error("No available mapping version found for protocol $protocolVersion")
        }

        val selectedVersion = available.max()
        if (selectedVersion.toInt() != protocolVersion) {
            // Runtime ids are only stable within a protocol, and this dataset stops at 1.21.80, so on a
            // modern server every block/item name below is resolved against an out-of-date table. Modules
            // that match on names (aura block checks, auto-eat item lookup) silently get wrong answers
            // rather than an error, so say it once per provider.
            Log.w(
                "MappingProvider",
                "No $assetPath mapping for protocol $protocolVersion; using $selectedVersion. " +
                    "Block/item names may resolve wrong until a table for this protocol is added.",
            )
        } else {
            Log.i("MappingProvider", "Selected mapping version $selectedVersion for protocol $protocolVersion")
        }
        return readMapping(selectedVersion)
    }

    abstract fun readMapping(version: Short): T
}