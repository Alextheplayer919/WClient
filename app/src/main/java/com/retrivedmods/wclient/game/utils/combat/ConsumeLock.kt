package com.retrivedmods.wclient.game.utils.combat

/**
 * Single shared "who owns the hand right now" lock for item-consume automation.
 *
 * AutoEat and AutoPot both drive the same hand through the same packet sequence;
 * without this they could interleave slot switches and ITEM_USE transactions in
 * the same tick and desync the server. Higher priority wins; a stale owner (crash,
 * dropped packets) is evicted automatically after [STALE_MS].
 */
object ConsumeLock {

    const val PRIORITY_EAT = 60
    const val PRIORITY_POT = 40

    private const val STALE_MS = 6000L

    @Volatile
    private var owner: String? = null

    @Volatile
    private var ownerPriority = 0

    @Volatile
    private var acquiredAt = 0L

    /** True while any consume sequence is running (used to hold aura swings). */
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

    /** True when another, higher- or equal-priority owner holds the lock. */
    @Synchronized
    fun isHeldByOther(name: String): Boolean {
        val current = owner ?: return false
        if (System.currentTimeMillis() - acquiredAt > STALE_MS) return false
        return current != name
    }

    @Synchronized
    fun release(name: String) {
        if (owner == name) {
            owner = null
            ownerPriority = 0
        }
    }
}
