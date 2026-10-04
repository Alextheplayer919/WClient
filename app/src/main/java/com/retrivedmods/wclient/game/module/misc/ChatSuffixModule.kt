package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.utils.ChatFormat
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

/** Prefixes ordinary outgoing chat with the configured text, separated by " | ", plus an optional random tail. */
class ChatSuffixModule : Module("Chat Suffix", ModuleCategory.Misc) {

    private val chatSuffix by stringValue("Chat Suffix", "E", emptyList())
    private val greenChat by boolValue("Green Chat", false)
    private val randomString by boolValue("Random String", true)

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || interceptablePacket.serverBound != true) return

        val packet = interceptablePacket.packet as? TextPacket ?: return
        if (packet.type != TextPacket.Type.CHAT) return

        val raw = packet.message?.toString()?.trim() ?: return
        // Leave slash commands and WClient dot commands exactly as entered.
        if (raw.isEmpty() || raw.startsWith("/") || raw.startsWith(".")) return

        val formatted = ChatFormat.format(
            message = raw,
            prefix = chatSuffix.ifEmpty { "E" },
            green = greenChat,
            random = randomString
        )

        // WRelay forwards the original bytes after its packet hook, so intercept that
        // packet and send a newly encoded copy with only the chat body changed. Keep
        // the client's source name and platform metadata intact; servers may validate it.
        interceptablePacket.intercept()
        val replacement = packet.clone().apply {
            message = formatted
        }
        session.serverBound(replacement)
        session.afterPacketBound(replacement)
    }
}
