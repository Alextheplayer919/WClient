package com.retrivedmods.wclient.game

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

data class InterceptablePacket(
    val packet: BedrockPacket,
    /** true = client -> server, false = server -> client, null = unknown */
    val serverBound: Boolean? = null
) {

    var isIntercepted = false
        private set

    fun intercept() {
        isIntercepted = true
    }

}
