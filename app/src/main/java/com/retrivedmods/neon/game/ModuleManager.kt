package com.retrivedmods.neon.game

import android.content.Context
import android.net.Uri
import com.retrivedmods.neon.application.AppContext
import com.retrivedmods.neon.game.module.combat.ACAModule
import com.retrivedmods.neon.game.module.combat.AntiKnockbackModule
import com.retrivedmods.neon.game.module.combat.EnemyHunterModule
import com.retrivedmods.neon.game.module.combat.HitAndRunModule
import com.retrivedmods.neon.game.module.combat.HitboxModule
import com.retrivedmods.neon.game.module.combat.KillauraModule
import com.retrivedmods.neon.game.module.combat.TriggerBotModule
import com.retrivedmods.neon.game.module.combat.WAuraModule
import com.retrivedmods.neon.game.module.combat.AutoEatModule
import com.retrivedmods.neon.game.module.combat.AutoFightModule
import com.retrivedmods.neon.game.module.combat.AutoHvHModule
import com.retrivedmods.neon.game.module.combat.AutoPotModule
import com.retrivedmods.neon.game.module.combat.AutoTotemModule
import com.retrivedmods.neon.game.module.combat.HotbarSwitcherModule
import com.retrivedmods.neon.game.module.combat.InfiniteAuraModule
import com.retrivedmods.neon.game.module.misc.ArrayListModule
import com.retrivedmods.neon.game.module.motion.NoClipModule
import com.retrivedmods.neon.game.module.misc.AutoDisconnectModule
import com.retrivedmods.neon.game.module.misc.CommandHandlerModule
import com.retrivedmods.neon.game.module.visual.CoordinatesModule
import com.retrivedmods.neon.game.module.misc.DesyncModule
import com.retrivedmods.neon.game.module.misc.FakeDeathModule
import com.retrivedmods.neon.game.module.misc.FakeXPModule
import com.retrivedmods.neon.game.module.misc.MinerModule
import com.retrivedmods.neon.game.module.misc.NoChatModule
import com.retrivedmods.neon.game.module.misc.PieChartModule
import com.retrivedmods.neon.game.module.misc.PositionLoggerModule
import com.retrivedmods.neon.game.module.misc.ReplayModule
import com.retrivedmods.neon.game.module.misc.ChestStealerModule
import com.retrivedmods.neon.game.module.misc.PopCounterModule
import com.retrivedmods.neon.game.module.misc.ChatSuffixModule
import com.retrivedmods.neon.game.module.misc.SpammerModule
import com.retrivedmods.neon.game.module.misc.ToggleSoundModule
import com.retrivedmods.neon.game.module.misc.WaterMarkModule
import com.retrivedmods.neon.game.module.world.AntiDebuffModule
import com.retrivedmods.neon.game.module.world.EffectsModule
import com.retrivedmods.neon.game.module.world.ParticlesModule
import com.retrivedmods.neon.game.module.world.TimeShiftModule
import com.retrivedmods.neon.game.module.world.WeatherControllerModule
import com.retrivedmods.neon.game.module.motion.AirJumpModule
import com.retrivedmods.neon.game.module.motion.AntiAFKModule
import com.retrivedmods.neon.game.module.motion.AutoWalkModule
import com.retrivedmods.neon.game.module.motion.BhopModule
import com.retrivedmods.neon.game.module.motion.FlyModule
import com.retrivedmods.neon.game.module.motion.HighJumpModule
import com.retrivedmods.neon.game.module.motion.JetPackModule
import com.retrivedmods.neon.game.module.motion.MotionFlyModule
import com.retrivedmods.neon.game.module.motion.OpFightBotModule
import com.retrivedmods.neon.game.module.motion.PlayerTPModule
import com.retrivedmods.neon.game.module.motion.SpeedModule
import com.retrivedmods.neon.game.module.motion.SpiderModule
import com.retrivedmods.neon.game.module.motion.SprintModule
import com.retrivedmods.neon.game.module.motion.TestFlyModule
import com.retrivedmods.neon.game.module.visual.CrosshairModule
import com.retrivedmods.neon.game.module.visual.DamageTextModule
import com.retrivedmods.neon.game.module.visual.ESPModule
import com.retrivedmods.neon.game.module.visual.FullbrightModule
import com.retrivedmods.neon.game.module.visual.MinimapModule
import com.retrivedmods.neon.game.module.visual.NetworkInfoModule
import com.retrivedmods.neon.game.module.visual.NoHurtCameraModule
import com.retrivedmods.neon.game.module.visual.PlayerJoinModule
import com.retrivedmods.neon.game.module.visual.SpeedDisplayModule
import com.retrivedmods.neon.game.module.visual.WorldStateModule
import com.retrivedmods.neon.game.module.visual.ZoomModule
import com.retrivedmods.neon.game.module.visual.TargetHudModule
import com.retrivedmods.neon.game.module.world.FreeCameraModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

object ModuleManager {

    private val _modules: MutableList<Module> = ArrayList()

    val modules: List<Module> = _modules

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        with(_modules) {
            // Combat
            add(WAuraModule())
            add(HotbarSwitcherModule())
            add(KillauraModule())
            add(AutoFightModule())
            add(InfiniteAuraModule())
            add(ACAModule())
            add(AutoTotemModule())
            add(AutoEatModule())
            add(AutoPotModule())
            add(AutoHvHModule())
            add(EnemyHunterModule())
            add(AntiKnockbackModule())

            add(HitAndRunModule())
            add(HitboxModule())
            add(TriggerBotModule())

            // Motion
            add(MotionFlyModule())
            add(TestFlyModule())
            add(OpFightBotModule())
            add(PlayerTPModule())
            add(FlyModule())
            add(SpeedModule())
            add(AirJumpModule())
            add(NoClipModule())
            add(JetPackModule())
            add(HighJumpModule())
            add(BhopModule())
            add(SprintModule())
            add(AutoWalkModule())
            add(AntiAFKModule())
            add(SpiderModule())

            // Visual
            add(DamageTextModule())
            add(ESPModule())
            add(PlayerJoinModule())
            add(ZoomModule())
            add(CoordinatesModule())
            add(NoHurtCameraModule())
            add(SpeedDisplayModule())
            add(NetworkInfoModule())
            add(WorldStateModule())
            add(MinimapModule())
            add(CrosshairModule())
            add(TargetHudModule())
            add(FullbrightModule())

            // World
            add(FreeCameraModule())
            add(TimeShiftModule())
            add(WeatherControllerModule())
            add(EffectsModule())
            add(ParticlesModule())
            add(AntiDebuffModule())

            // Misc

            add(AutoDisconnectModule())
            add(ArrayListModule())
            add(ToggleSoundModule())
            add(ChestStealerModule())
            add(DesyncModule())
            add(SpammerModule())
            add(PopCounterModule())
            add(ChatSuffixModule())
            add(WaterMarkModule())
            add(PositionLoggerModule())
            add(NoChatModule())
            add(CommandHandlerModule())
            add(ReplayModule())
            add(PieChartModule())
            add(FakeDeathModule())
            add(FakeXPModule())
            add(MinerModule())
        }
    }

    fun saveConfig() {

        if (!AppContext.isInitialized) {
            return
        }

        val configsDir = AppContext.instance.filesDir.resolve("configs")
        configsDir.mkdirs()

        val config = configsDir.resolve("UserConfig.json")
        val jsonObject = buildJsonObject {
            put("modules", buildJsonObject {
                _modules.forEach {
                    if (it.private) {
                        return@forEach
                    }
                    put(it.name, it.toJson())
                }
            })
        }

        config.writeText(json.encodeToString(JsonObject.serializer(), jsonObject))
    }

    fun loadConfig() {

        if (!AppContext.isInitialized) {
            return
        }

        val configsDir = AppContext.instance.filesDir.resolve("configs")
        configsDir.mkdirs()

        val config = configsDir.resolve("UserConfig.json")
        if (!config.exists()) {
            return
        }

        val jsonString = config.readText()
        if (jsonString.isEmpty()) {
            return
        }

        try {
            val jsonObject = json.parseToJsonElement(jsonString).jsonObject
            val modules = jsonObject["modules"]?.jsonObject ?: return

            _modules.forEach { module ->
                moduleConfig(modules, module)?.let(module::fromJson)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Keep renamed modules compatible with profiles saved under their previous IDs. */
    private fun moduleConfig(configs: JsonObject, module: Module): JsonObject? {
        (configs[module.name] as? JsonObject)?.let { return it }
        if (module is KillauraModule) {
            return listOf("killaura", "kiwi_killaura")
                .firstNotNullOfOrNull { configs[it] as? JsonObject }
        }
        return null
    }

    fun exportConfig(): String {
        val jsonObject = buildJsonObject {
            put("modules", buildJsonObject {
                _modules.forEach {
                    if (it.private) {
                        return@forEach
                    }
                    put(it.name, it.toJson())
                }
            })
        }
        return json.encodeToString(JsonObject.serializer(), jsonObject)
    }

    fun importConfig(configStr: String) {
        try {
            val jsonObject = json.parseToJsonElement(configStr).jsonObject
            val modules = jsonObject["modules"]?.jsonObject ?: return

            _modules.forEach { module ->
                moduleConfig(modules, module)?.let(module::fromJson)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid config format: ${e.message}")
        }
    }

    fun exportConfigToFile(context: Context, filePath: String): Boolean {
        return try {
            val file = if (filePath.contains("/")) {
                File(filePath)
            } else {
                val configsDir = context.getExternalFilesDir("configs")
                configsDir?.mkdirs()
                File(configsDir, if (filePath.endsWith(".json")) filePath else "$filePath.json")
            }

            file.parentFile?.mkdirs()
            file.writeText(exportConfig())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getNeonConfigsDirectory(): File? {
        return try {
            // Single source of truth: the same directory the in-game profile
            // system (ConfigManager) resolves, so exports and saved profiles
            // always end up in one place.
            com.retrivedmods.neon.game.config.ConfigManager.configsDirectory
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun importConfigFromFile(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val configStr = input.bufferedReader().readText()
                importConfig(configStr)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}