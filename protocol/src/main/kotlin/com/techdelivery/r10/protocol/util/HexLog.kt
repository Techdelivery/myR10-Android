package com.techdelivery.r10.protocol.util

enum class HexDirection { TX, RX }

data class HexEntry(val direction: HexDirection, val timestampMs: Long, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HexEntry) return false
        return direction == other.direction &&
            timestampMs == other.timestampMs &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var h = direction.hashCode()
        h = 31 * h + timestampMs.hashCode()
        h = 31 * h + bytes.contentHashCode()
        return h
    }
}

/**
 * Thread-safe ring buffer of TX/RX byte events, newest last, for the in-app hex
 * log pane and golden-file export (DESIGN §5 / plan M0-3). Oldest entries are
 * evicted once [capacity] is exceeded.
 */
class HexLog(private val capacity: Int = 4096) {
    private val ring = ArrayDeque<HexEntry>()
    private val lock = Any()

    /** Optional live listener, invoked for each new entry (outside the lock). */
    @Volatile
    var listener: ((HexEntry) -> Unit)? = null

    fun log(direction: HexDirection, bytes: ByteArray, timestampMs: Long = System.currentTimeMillis()) {
        val copy = bytes.copyOf()
        val entry = HexEntry(direction, timestampMs, copy)
        synchronized(lock) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(entry)
        }
        listener?.invoke(entry)
    }

    fun tx(bytes: ByteArray, timestampMs: Long = System.currentTimeMillis()) = log(HexDirection.TX, bytes, timestampMs)

    fun rx(bytes: ByteArray, timestampMs: Long = System.currentTimeMillis()) = log(HexDirection.RX, bytes, timestampMs)

    /** Non-destructive copy of current entries, oldest -> newest. */
    fun snapshot(): List<HexEntry> = synchronized(lock) { ring.toList() }

    fun size(): Int = synchronized(lock) { ring.size }

    fun clear() = synchronized(lock) { ring.clear() }

    /** Golden-file format: one `TX <hex>` / `RX <hex>` line per entry. */
    fun exportHex(): String = snapshot().joinToString("\n") { e ->
        "${e.direction.name} ${ByteUtil.toHex(e.bytes)}"
    }
}
