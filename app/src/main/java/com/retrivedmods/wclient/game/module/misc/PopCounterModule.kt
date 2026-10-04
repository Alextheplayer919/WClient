package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.GameSession
import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Player
import com.retrivedmods.wclient.game.friend.FriendManager
import com.retrivedmods.wclient.game.utils.ChatFormat
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.packet.*

class PopCounterModule : Module("PopCounter", ModuleCategory.Misc) {
    private val sendChat by boolValue("Send Chat", false)
    private val greenChat by boolValue("Green Chat", false)
    // Only enable on servers that do not broadcast CONSUME_TOTEM events.
    private val offhandPolling by boolValue("Offhand Polling", false)

    private val lastHadTotem = mutableMapOf<Long, Boolean>()
    private val counts = mutableMapOf<java.util.UUID, Int>()
    private val recentPopMs = mutableMapOf<Long, Long>()
    private val messages = java.util.ArrayDeque<String>()
    private var trackedSession: GameSession? = null
    private var lastPollMs = 0L
    private var lastSendMs = 0L

    @Synchronized
    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return
        if (trackedSession !== session) {
            reset()
            trackedSession = session
        }
        val packet = interceptablePacket.packet
        if (interceptablePacket.serverBound == false) {
            when (packet) {
                is StartGamePacket -> reset()
                is ChangeDimensionPacket -> reset()
                is EntityEventPacket -> {
                    if (packet.runtimeEntityId == session.localPlayer.runtimeEntityId) {
                        if (packet.type == EntityEventType.DEATH) reset()
                    } else if (packet.type == EntityEventType.CONSUME_TOTEM) {
                        val player = session.level.entityMap[packet.runtimeEntityId] as? Player
                        if (player != null && eligible(player)) recordPop(player)
                    }
                }
                // Remote inventories aren't normally tracked unless ESP is enabled.
                is MobEquipmentPacket -> session.level.entityMap[packet.runtimeEntityId]
                    ?.inventory?.onPacketBound(packet)
            }
        }
        // Movement packets provide regular ticks without leaking background jobs.
        if (packet is PlayerAuthInputPacket && interceptablePacket.serverBound == true) {
            val now = System.currentTimeMillis()
            if (now - lastPollMs >= 150L) {
                lastPollMs = now
                pollTotems()
            }
            if (!sendChat) messages.clear()
            if (now - lastSendMs >= 600L && messages.isNotEmpty()) {
                lastSendMs = now
                val message = messages.removeFirst()
                session.serverBound(TextPacket().apply {
                    type = TextPacket.Type.CHAT
                    needsTranslation = false
                    sourceName = ""
                    xuid = ""
                    platformChatId = ""
                    this.message = message
                })
            }
        }
    }

    private fun eligible(player: Player): Boolean =
        player.runtimeEntityId != session.localPlayer.runtimeEntityId &&
            !FriendManager.isFriend(player.uuid) && player.distance(session.localPlayer) <= 100f

    private fun pollTotems() {
        val active = mutableSetOf<Long>()
        session.level.entityMap.values.filterIsInstance<Player>().forEach { player ->
            if (!eligible(player)) return@forEach
            val id = player.runtimeEntityId
            active.add(id)
            val hasTotem = player.inventory.offhand.definition?.identifier == "minecraft:totem_of_undying"
            if (offhandPolling && lastHadTotem[id] == true && !hasTotem) recordPop(player)
            lastHadTotem[id] = hasTotem
        }
        lastHadTotem.keys.retainAll(active)
        recentPopMs.keys.retainAll(active)
    }

    private fun recordPop(player: Player) {
        val now = System.currentTimeMillis()
        val previous = recentPopMs[player.runtimeEntityId]
        if (previous != null && now - previous < 1200L) return
        recentPopMs[player.runtimeEntityId] = now
        val count = (counts[player.uuid] ?: 0) + 1
        counts[player.uuid] = count
        val name = player.username.ifBlank { "unknown" }
        session.displayClientMessage("§7[E] $name popped $count totems")
        if (!sendChat) return
        val message = POP_MESSAGES.random().replace("{name}", name).replace("{count}", count.toString())
        if (messages.size >= 30) messages.removeFirst()
        messages.addLast(ChatFormat.format(message, green = greenChat))
    }

    @Synchronized
    override fun onEnabled() {
        reset()
        super.onEnabled()
    }

    @Synchronized
    override fun onDisabled() {
        reset()
        super.onDisabled()
    }

    @Synchronized
    override fun onDisconnect(reason: String) {
        reset()
        trackedSession = null
    }

    private fun reset() {
        lastHadTotem.clear()
        counts.clear()
        recentPopMs.clear()
        messages.clear()
        lastPollMs = 0L
        lastSendMs = 0L
    }

    companion object {
        private val POP_MESSAGES = listOf(
            "@{name} popped {count} totems | E",
            "@{name} there goes your totem! {count} popped | E",
            "@{name} needs {count} totems to survive the wrath of E",
            "@{name} another totem went to E's pop list, {count} popped",
            "@{name} totem #{count} popped, just give up atp | E",
            "@{name} decided to defy E and popped {count} totems as punishment",
            "@{name} popping totems like balloons | {count}x already | E",
            "@{name} is a totem factory | {count}x popped | E",
            "@{name} skill issue on another level, {count} totems popped | E",
            "@{name} {count} totems down, thanks to E",
            "Your totem can save your life but not your pride @{name} {count} pops | E",
            "@{name} bro's playing totem simulator | {count}x popped | E",
            "@{name} THE TOTEM CANNOT SAVE YOU FOREVER! {count} pops | E",
            "@{name} another totem by E | {count} already"
        )
    }
}
