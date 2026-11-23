package com.ridehailing.mesh.protocol

/**
 * Fragmentation for BLE MTUs (usable payload 20-185 bytes negotiated). Fragment header:
 * msgIdHash 8B (truncated SHA-256 of msgId) + index u16 + total u16 + data.
 * Reassembly with timeout, per-message byte cap and per-origin in-flight cap (anti-DoS).
 */
object Fragmenter {
    const val FRAG_HEADER_LEN = 12

    fun split(msgId: ByteArray, payload: ByteArray, usableMtu: Int): List<ByteArray> {
        require(usableMtu > FRAG_HEADER_LEN) { "mtu too small" }
        require(msgId.size == 16)
        val chunk = usableMtu - FRAG_HEADER_LEN
        val total = ((payload.size + chunk - 1) / chunk).coerceAtLeast(1)
        val hash = msgIdHash(msgId)
        val frags = ArrayList<ByteArray>(total)
        var off = 0
        for (i in 0 until total) {
            val len = minOf(chunk, payload.size - off)
            val frag = ByteArray(FRAG_HEADER_LEN + len)
            System.arraycopy(hash, 0, frag, 0, 8)
            frag[8] = ((i ushr 8) and 0xFF).toByte(); frag[9] = (i and 0xFF).toByte()
            frag[10] = ((total ushr 8) and 0xFF).toByte(); frag[11] = (total and 0xFF).toByte()
            if (len > 0) System.arraycopy(payload, off, frag, FRAG_HEADER_LEN, len)
            off += len
            frags.add(frag)
        }
        return frags
    }

    fun msgIdHash(msgId: ByteArray): ByteArray {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(msgId)
        return d.copyOfRange(0, 8)
    }
}

sealed class ReassemblyResult {
    data class Complete(val payload: ByteArray) : ReassemblyResult()
    data object Waiting : ReassemblyResult()
    data class Rejected(val reason: String) : ReassemblyResult()
}

class Reassembler(
    private val maxMessageBytes: Int = 4096,
    private val maxInFlightPerOrigin: Int = 8,
    private val timeoutMs: Long = 10_000,
) {
    private class State(val total: Int, val firstSeenMs: Long) {
        var bytes = 0
        val parts = HashMap<Int, ByteArray>()
    }

    // NOTE: ByteArray equals/hashCode are identity-based; keys must be content strings.
    private val messages = HashMap<String, State>()
    private val perOrigin = HashMap<String, Int>()

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun add(originId: ByteArray, fragment: ByteArray, nowMs: Long): ReassemblyResult {
        if (fragment.size < Fragmenter.FRAG_HEADER_LEN) return ReassemblyResult.Rejected("short fragment")
        val hash = fragment.copyOfRange(0, 8)
        val key = hex(hash)
        val index = ((fragment[8].toInt() and 0xFF) shl 8) or (fragment[9].toInt() and 0xFF)
        val total = ((fragment[10].toInt() and 0xFF) shl 8) or (fragment[11].toInt() and 0xFF)
        if (total == 0 || total > maxInFlightPerOrigin * 8) return ReassemblyResult.Rejected("bad total $total")
        val originKey = hex(originId)
        val state = messages.getOrPut(key) {
            if (perOrigin.getOrDefault(originKey, 0) >= maxInFlightPerOrigin) {
                return ReassemblyResult.Rejected("per-origin in-flight cap")
            }
            perOrigin[originKey] = perOrigin.getOrDefault(originKey, 0) + 1
            State(total, nowMs)
        }
        if (state.total != total) return ReassemblyResult.Rejected("total mismatch")
        if (!state.parts.containsKey(index)) {
            val data = fragment.copyOfRange(Fragmenter.FRAG_HEADER_LEN, fragment.size)
            if (state.bytes + data.size > maxMessageBytes) return ReassemblyResult.Rejected("byte cap")
            state.parts[index] = data
            state.bytes += data.size
        }
        if (state.parts.size == state.total) {
            val payload = ByteArray(state.bytes)
            var off = 0
            for (i in 0 until state.total) {
                val part = state.parts[i] ?: return ReassemblyResult.Rejected("missing $i")
                System.arraycopy(part, 0, payload, off, part.size); off += part.size
            }
            messages.remove(key)
            perOrigin[originKey] = perOrigin.getOrDefault(originKey, 1) - 1
            if (perOrigin[originKey]!! <= 0) perOrigin.remove(originKey)
            return ReassemblyResult.Complete(payload)
        }
        return ReassemblyResult.Waiting
    }

    /** Drops messages older than the timeout; returns count evicted. */
    fun sweep(nowMs: Long): Int {
        val expired = messages.filterValues { nowMs - it.firstSeenMs > timeoutMs }.keys.toList()
        messages.keys.removeAll(expired.toSet())
        return expired.size
    }
}
