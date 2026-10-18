package com.retrivedmods.neon.game.module.misc

import com.retrivedmods.neon.game.InterceptablePacket
import com.retrivedmods.neon.game.Module
import com.retrivedmods.neon.game.ModuleCategory

class ToggleSoundModule : Module(
    name = "toggle_sounds",
    category = ModuleCategory.Misc,
    defaultEnabled = false
) {
    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
    }
}
