package com.ridehailing.backend.events

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

/**
 * Append-only, tamper-evident event chain (B-04 core).
 *
 * hash_i = SHA256(prevHash | seq | type | canonical(payload) | occurredAt)
 *
 * Genesis: prevHash of the first event is GENESIS. verify() recomputes every link;
 * any tampering, reordering, or sequence gap breaks verification. Canonicalization
 * sorts object keys recursively so the same logical payload always hashes identically.
 *
 * Open for the PG-backed store (P1-3e): PgEventStore overrides append() to persist
 * every record into event_log while the in-memory link stays the runtime truth.
 */
open class EventChain(
    private val clock: Clock = Clock.systemUTC(),
    startingEvents: List<EventRecord> = emptyList(),
) {
    private val events = startingEvents.toMutableList()
    val tailHash: String get() = events.lastOrNull()?.hash ?: GENESIS

    open fun append(type: String, payload: Map<String, Any?>, occurredAt: Instant = clock.instant()): EventRecord {
        val seq = (events.lastOrNull()?.seq ?: 0L) + 1L
        val canonical = canonicalJson(payload)
        val prevHash = tailHash
        val record = EventRecord(
            seq = seq,
            type = type,
            payloadJson = canonical,
            occurredAt = occurredAt.toString(),
            prevHash = prevHash,
            hash = hashOf(prevHash, seq, type, canonical, occurredAt.toString()),
        )
        events.add(record)
        return record
    }

    /** Recomputes the whole chain; returns null if valid, else the first bad index. */
    fun verify(): Int? {
        var prev = GENESIS
        for ((index, e) in events.withIndex()) {
            val expectedSeq = index + 1L
            val recomputed = hashOf(prev, e.seq, e.type, e.payloadJson, e.occurredAt)
            if (e.seq != expectedSeq) return index
            if (e.prevHash != prev) return index
            if (e.hash != recomputed) return index
            prev = e.hash
        }
        return null
    }

    fun snapshot(): List<EventRecord> = events.toList()

    companion object {
        const val GENESIS = "GENESIS"

        fun hashOf(prevHash: String, seq: Long, type: String, canonicalPayload: String, occurredAt: String): String =
            sha256Hex("$prevHash|$seq|$type|$canonicalPayload|$occurredAt")

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Deterministic JSON: object keys sorted recursively; arrays keep order. */
        fun canonicalJson(value: Any?): String = when (value) {
            null -> "null"
            is String -> quote(value)
            is Boolean -> value.toString()
            is Int, is Long, is Short, is Byte -> value.toString()
            is Double, is Float ->
                if ((value as Number).toDouble() == (value as Number).toDouble().toLong().toDouble()) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
            is Map<*, *> -> value.entries
                .sortedBy { it.key.toString() }
                .joinToString(",", "{", "}") { "${quote(it.key.toString())}:${canonicalJson(it.value)}" }
            is List<*> -> value.joinToString(",", "[", "]") { canonicalJson(it) }
            else -> quote(value.toString())
        }

        private fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else ->
                        if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            return sb.append("\"").toString()
        }
    }
}

data class EventRecord(
    val seq: Long,
    val type: String,
    val payloadJson: String,
    val occurredAt: String,
    val prevHash: String,
    val hash: String,
)
