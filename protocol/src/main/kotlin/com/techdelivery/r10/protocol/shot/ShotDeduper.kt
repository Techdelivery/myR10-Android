package com.techdelivery.r10.protocol.shot

/**
 * Shot dedup by the device's `shot_id` (DESIGN §1 / §7.2: "duplicate = ignore").
 *
 * Thread-safe. The instance is scoped to one connection: [reset] is called on
 * every new connect because a power-cycled R10 restarts its shot-id sequence,
 * and carrying ids across connections would silently drop a real new shot.
 * Cross-session dedup is the persistence layer's job (unique index, DESIGN §8).
 */
class ShotDeduper {
    private val seen = mutableSetOf<Int>()

    val seenCount: Int get() = synchronized(this) { seen.size }

    /** True when [shotId] has not been seen yet (and records it). */
    fun accept(shotId: Int): Boolean = synchronized(this) { seen.add(shotId) }

    fun isSeen(shotId: Int): Boolean = synchronized(this) { shotId in seen }

    fun reset() = synchronized(this) { seen.clear() }
}
