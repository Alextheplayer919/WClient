package com.retrivedmods.wrelay.listener

import com.retrivedmods.wrelay.WRelaySession
import com.retrivedmods.wrelay.util.AuthUtils
import com.retrivedmods.wrelay.util.refresh
import com.retrivedmods.wtrace.WTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
import org.cloudburstmc.protocol.bedrock.util.JsonUtils
import org.jose4j.json.JsonUtil
import org.jose4j.json.internal.json_simple.JSONObject
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwx.HeaderParameterNames
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


@Suppress("MemberVisibilityCanBePrivate")
class OnlineLoginPacketListener(
    val wRelaySession: WRelaySession,
    fullBedrockSession: StepFullBedrockSession.FullBedrockSession
) : WRelayPacketListener {

    /**
     * Refreshed off the Netty event loop so a stale token cannot stall the game facing session.
     * Volatile because it is written on Dispatchers.IO and read from the packet pipeline.
     */
    @Volatile
    private var fullBedrockSession: StepFullBedrockSession.FullBedrockSession = fullBedrockSession

    private var skinData: JSONObject? = null

    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        if (packet is LoginPacket) {
            println("Processing login packet")
            val startedAt = System.currentTimeMillis()
            WTrace.info(
                "login",
                "the game client sent LoginPacket (protocol ${packet.protocolVersion}); " +
                    "the upstream link is opened from here, nothing else is forwarded until it is up"
            )

            try {
                val jws = JsonWebSignature()
                jws.compactSerialization = packet.clientJwt

                skinData = JSONObject(JsonUtil.parseJson(jws.unverifiedPayload))
                connectServer()
                WTrace.debug("login", "login JWT parsed and upstream connect dispatched (+${System.currentTimeMillis() - startedAt}ms)")
            } catch (e: Exception) {
                println("Failed to process login packet: ${e.message}")
                e.printStackTrace()
                WTrace.error("login", "could not parse the game client's LoginPacket: ${e.message}")
                wRelaySession.server.disconnect("Failed to process login: ${e.message}")
                return true
            }
            return true
        }
        return false
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        if (packet is NetworkSettingsPacket) {
            val threshold = packet.compressionThreshold
            if (threshold > 0) {
                wRelaySession.client!!.setCompression(packet.compressionAlgorithm)
                println("Compression enabled: ${packet.compressionAlgorithm}, threshold: $threshold")
                WTrace.info("login", "upstream compression enabled: ${packet.compressionAlgorithm}, threshold=$threshold")
            } else {
                wRelaySession.client!!.setCompression(PacketCompressionAlgorithm.NONE)
                println("Compression disabled")
                WTrace.info("login", "upstream compression disabled by the server")
            }
            val chainStartedAt = System.currentTimeMillis()

            try {
                val chain = AuthUtils.fetchOnlineChain(fullBedrockSession)
                val skinData =
                    AuthUtils.fetchOnlineSkinData(
                        fullBedrockSession,
                        skinData!!,
                        wRelaySession.wRelay.remoteAddress!!
                    )

                val loginPacket = LoginPacket()
                loginPacket.protocolVersion = wRelaySession.server.codec.protocolVersion
                loginPacket.authPayload = CertificateChainPayload(chain, AuthType.FULL)
                loginPacket.clientJwt = skinData
                wRelaySession.serverBoundImmediately(loginPacket)

                println("Login packet sent successfully")
                WTrace.info(
                    "login",
                    "relayed login to the server ${System.currentTimeMillis() - chainStartedAt}ms after the " +
                        "network settings exchange (chain + skin data fetch + signing)"
                )
            } catch (e: Throwable) {
                println("Login failed: ${e.message}")
                e.printStackTrace()
                WTrace.error("login", "authentication failed: ${e.message}")
                wRelaySession.server.disconnect("Authentication failed: ${e.message}")
                return true
            }

            return true
        }
        if (packet is ServerToClientHandshakePacket) {
            try {
                val parts = packet.jwt.split(".")
                if (parts.size != 3) {
                    throw Exception("Invalid JWT format")
                }
                
                val headerJson = String(java.util.Base64.getUrlDecoder().decode(parts[0]))
                val payloadJson = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                
                val header = JSONObject(JsonUtil.parseJson(headerJson))
                val payload = JSONObject(JsonUtil.parseJson(payloadJson))
                
                val x5u = header.get("x5u") as? String ?: throw Exception("Missing x5u in header")
                val serverKey = EncryptionUtils.parseKey(x5u)
                
                val saltString = payload.get("salt") as? String ?: throw Exception("Missing salt in payload")
                val salt = java.util.Base64.getDecoder().decode(saltString)
                
                val key = EncryptionUtils.getSecretKey(
                    fullBedrockSession.mcChain.privateKey, 
                    serverKey,
                    salt
                )
                
                wRelaySession.client!!.enableEncryption(key)
                println("Encryption enabled successfully")
                WTrace.info("login", "derived the AES key from ServerToClientHandshake; ClientToServerHandshake sent")

                wRelaySession.serverBoundImmediately(ClientToServerHandshakePacket())
            } catch (e: Exception) {
                println("Handshake failed: ${e.message}")
                e.printStackTrace()
                WTrace.error("login", "ServerToClientHandshake handling failed: ${e.message}")
                wRelaySession.server.disconnect("Handshake failed: ${e.message}")
                return true
            }
            return true
        }
        return false
    }

    /**
     * Kicks off the upstream connection. The token refresh and the RakNet connect both perform
     * network I/O: they must never run on the Netty event loop. Doing the refresh inline used to
     * block the game facing session for tens of seconds on the first join, which the game reports
     * as a "creeper" connection timeout and which appears to be fixed by simply trying again.
     */
    private fun connectServer() {
        CoroutineScope(Dispatchers.IO).launch {
            if (!ensureSessionFresh()) {
                return@launch
            }

            try {
                wRelaySession.wRelay.connectToServer {
                    println("Connected to server, sending network settings request")

                    try {
                        val packet = RequestNetworkSettingsPacket()
                        packet.protocolVersion = wRelaySession.server.codec.protocolVersion
                        wRelaySession.serverBoundImmediately(packet)
                        println("Network settings request sent")
                    } catch (e: Exception) {
                        println("Failed to send network settings request: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                println("Failed to connect to server: ${e.message}")
                e.printStackTrace()
                wRelaySession.server.disconnect("Failed to connect to server: ${e.message}")
            }
        }
    }

    private suspend fun ensureSessionFresh(): Boolean {
        if (!fullBedrockSession.isExpired) {
            return true
        }

        println("Session expired, attempting to refresh tokens...")
        val startedAt = System.currentTimeMillis()
        WTrace.warn("auth", "cached Xbox session expired; refreshing before the join can continue")
        return try {
            fullBedrockSession = fullBedrockSession.refresh()
            println("Successfully refreshed session for: ${fullBedrockSession.mcChain.displayName}")
            WTrace.info("auth", "session refreshed in ${System.currentTimeMillis() - startedAt}ms, join can continue")
            true
        } catch (e: Exception) {
            println("Failed to refresh session: ${e.message}")
            e.printStackTrace()
            WTrace.error("auth", "token refresh failed after ${System.currentTimeMillis() - startedAt}ms: ${e.message}")
            wRelaySession.server.disconnect(
                "Your session has expired and could not be refreshed. Please re-login in the W Client."
            )
            false
        }
    }

}