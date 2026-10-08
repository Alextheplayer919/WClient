package com.retrivedmods.wclient.game.utils.combat

/**
 * Single shared "who owns the server-side hotbar right now" lock for modules that silently
 * swap the held slot (weapons, blocks).
 *
 * Two modules swapping in the same tick would interleave their MobEquipment packets and the
 * other module's transaction would reference an item the server no longer holds in that slot
 * (voided hits / mis-placed blocks). Same shape as [ConsumeLock]: named owners, priority,
 * and stale eviction so an owner that crashes mid-sequence cannot wedge the lock.
 *
 * Everything here runs on the relay thread; the @Volatile fields are a courtesy for readers
 * on other threads (UI status displays).
 */
object HotbarLock {

    /** Weapon swaps for attacks — time-critical, outranks placement. */
    const val PRIORITY_WEAPON = 70

    /** Block placement (feet surround) — yields to attacks. */
    const val PRIORITY_BLOCK = 50

    private const val STALE_MS = 3000L

    @Volatile
    private var owner: String? = null

    @Volatile
    private var ownerPriority = 0

    @Volatile
    private var acquiredAt = 0L

    /** True while any hotbar-swap sequence is running. */
    val isBusy: Boolean
        get() {
            val current = owner ?: return false
            if (System.currentTimeMillis() - acquiredAt > STALE_MS) return false
            return current.isNotEmpty()
        }

    @Synchronized
    fun tryAcquire(name: String, priority: Int): Boolean {
        val now = System.currentTimeMillis()
        val current = owner
        if (current == null || current == name || now - acquiredAt > STALE_MS || priority > ownerPriority) {
            owner = name
            ownerPriority = priority
            acquiredAt = now
            return true
        }
        return false
    }

    @Synchronized
    fun release(name: String) {
        if (owner == name) {
            owner = null
            ownerPriority = 0
        }
    }
}
