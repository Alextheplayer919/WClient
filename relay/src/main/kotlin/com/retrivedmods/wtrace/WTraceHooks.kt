package com.retrivedmods.wtrace

import org.cloudburstmc.netty.trace.RakTrace
import org.cloudburstmc.protocol.bedrock.netty.trace.BedrockTrace

/**
 * Wires the Java 8 trace hooks of the vendored modules into the console.
 *
 * The flags are late bound, so flipping "Network trace" or "Datagram trace" in the console takes
 * effect immediately without reinstalling anything.
 */
object WTraceHooks {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) {
            return
        }
        installed = true

        RakTrace.setSink { level, tag, message, detail ->
            WTrace.log(WLevel.fromOrdinal(level), WKind.NET, "raknet/$tag", message, detail)
        }
        RakTrace.setEnabledFlag { WTrace.enabled && WTrace.netTrace }
        RakTrace.setVerboseFlag { WTrace.datagramTrace }

        BedrockTrace.setSink { level, tag, message, detail ->
            WTrace.log(WLevel.fromOrdinal(level), WKind.NET, "bedrock/$tag", message, detail)
        }
        BedrockTrace.setEnabledFlag { WTrace.enabled && WTrace.netTrace }
        BedrockTrace.setVerboseFlag { WTrace.datagramTrace }

        WTrace.info("console", "trace hooks installed (raknet + bedrock batch/compression/encryption)")
    }
}
