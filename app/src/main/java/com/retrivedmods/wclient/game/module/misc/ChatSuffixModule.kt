package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.utils.ChatFormat
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

/** Appends a configurable tag plus random junk tail to every outgoing chat message. */
class ChatSuffixModule : Module("Chat Suffix", ModuleCategory.Misc) {

    private val chatSuffix by stringValue("Chat Suffix", "E", emptyList())
    private val greenChat by boolValue("Green Chat", false)
    private val randomString by boolValue("Random String", true)

    private fun buildTextPacket(message: String): TextPacket = TextPacket().apply {
        type = TextPacket.Type.CHAT
        isNeedsTranslation = false
        sourceName = "__ox_internal__"
        xuid = ""
        platformChatId = ""
        this.message = message
        filteredMessage = ""
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return
        if (interceptablePacket.serverBound != true) return
        val p = interceptablePacket.packet as? TextPacket ?: return
        if (p.type != TextPacket.Type.CHAT) return
        // Ignore our own re-injected packets so we don't loop.
        if (p.sourceName == "__ox_internal__") return

        val raw = p.message?.trim() ?: return
        if (raw.isEmpty() || raw.startsWith("/") || raw.startsWith(".")) return

        val suffix = chatSuffix.ifEmpty { "E" }
        val body = if (greenChat) raw.removePrefix("> ").removePrefix(">") else raw
        val base = (if (greenChat) "> " else "") + body
        val formatted = if (randomString) "$base | $suffix | ${ChatFormat.randomString()}" else "$base | $suffix"

        // Cancel the original packet and re-send a fresh one so the relay forwards the
        // modified bytes instead of the untouched originals.
        interceptablePacket.intercept()
        val replacement = buildTextPacket(formatted)
        session.serverBound(replacement)
        session.afterPacketBound(replacement)
    }
}
