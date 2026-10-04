package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.utils.ChatFormat
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

/** Despite the name, the custom text goes at the START, as requested. */
class ChatSuffixModule : Module("Chat Suffix", ModuleCategory.Misc) {
    private val text by stringValue("Text", "E", emptyList())
    private val greenChat by boolValue("Green Chat", false)
    private val randomString by boolValue("Random String", true)

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || interceptablePacket.serverBound != true) return
        val packet = interceptablePacket.packet as? TextPacket ?: return
        if (packet.type != TextPacket.Type.CHAT) return
        // Do not rewrite server commands or the client's dot commands.
        val message = packet.message.toString()
        if (message.trimStart().let { it.startsWith("/") || it.startsWith(".") }) return
        val formatted = ChatFormat.format(message, text, greenChat, randomString)
        if (formatted == message) return

        // The relay forwards untouched packet bytes. Drop the original and send
        // the modified TextPacket so the prefix actually reaches the server.
        packet.message = formatted
        interceptablePacket.intercept()
        session.serverBound(packet)
        session.afterPacketBound(packet)
    }
}
