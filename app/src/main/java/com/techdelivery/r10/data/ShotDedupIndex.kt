package com.techdelivery.r10.data

import com.techdelivery.r10.protocol.shot.Shot

/**
 * Bounded LRU set of dedup keys for cross-session duplicate rejection
 * (DESIGN §8, ROADMAP R5).
 *
 * Extracted from [ShotCsvStore] because it is a different job with different rules:
 * the store owns durability, this owns remembering. Keeping them apart is what lets
 * the store rebuild the index from the file after a rewrite without reimplementing
 * eviction, and lets eviction be tested without a file.
 *
 * The key is the raw proto payload plus the shot id — see [ShotCsvStore]'s dedup note
 * for why `shot_id` alone is not identity across a power cycle.
 */
internal class ShotDedupIndex(private val window: Int) {

    private val keys = HashSet<String>()
    private val order = ArrayDeque<String>()

    /** True when this exact payload was already stored. Touches the key if so. */
    fun contains(key: String): Boolean {
        if (key !in keys) return false
        // Re-observed keys move to the most-recent end, so eviction is LRU rather
        // than FIFO. Without this a key that keeps getting replayed could be evicted
        // while long-dead ones stay.
        if (order.remove(key)) order.addLast(key)
        return true
    }

    fun add(key: String) {
        if (!keys.add(key)) return
        order.addLast(key)
        evict()
    }

    fun clear() {
        keys.clear()
        order.clear()
    }

    /**
     * Re-seed from the rows that exist in the file.
     *
     * Called after every rewrite. Left stale, a deleted shot's key would suppress the
     * device re-pushing that exact shot for the rest of the session — a silent data
     * loss that looks like "the R10 stopped sending".
     */
    fun seedFrom(shots: List<Shot>, keyOf: (Shot) -> String?) {
        clear()
        shots.forEach { keyOf(it)?.let(::add) }
    }

    private fun evict() {
        while (order.size > window) {
            keys.remove(order.removeFirst())
        }
    }
}
