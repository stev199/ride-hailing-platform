package com.ridehailing.mesh.protocol

import java.io.ByteArrayOutputStream

/**
 * Binary codec. decode() NEVER throws: malformed input yields DecodeResult.Malformed,
 * unknown protocol versions yield DecodeResult.UnknownVersion (graceful ignore, per spec).
 */
sealed class DecodeResult {
    data class Ok(val envelope: Envelope) : DecodeResult()
    data class Malformed(val reason: String) : DecodeResult()
    data class UnknownVersion(val version: Int) : DecodeResult()
}

object Codec {
    private const val ORIGIN_SIG_LEN = 64
    private const val HOP_MAC_LEN = 16
    private const val BREADCRUMB_LEN = 6
    private const val MAX_PAYLOAD = 4096
    private const val MAX_HOPS_WIRE = 255

    fun encode(env: Envelope): ByteArray {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Int) { out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        fun bytes(b: ByteArray) { out.write(b); }

        u8(env.version)
        u8(env.type.id)
        u8(env.flags)
        require(env.msgId.size == 16) { "msgId must be 16 bytes" }
        require(env.originId.size == 8) { "originId must be 8 bytes" }
        bytes(env.msgId)
        bytes(env.originId)
        u32(env.originEpoch)
        u8(env.destination.kind.id)
        if (env.destination.kind == DestKind.DRIVER_ID || env.destination.kind == DestKind.NODE) {
            require(env.destination.id != null && env.destination.id.size == 8) { "destId required (8B) for ${env.destination.kind}" }
            bytes(env.destination.id!!)
        }
        u32(env.createdAtSec)
        u16(env.ttlSec)
        u8(env.maxHops)
        require(env.payload.size <= MAX_PAYLOAD) { "payload too large" }
        u16(env.payload.size)
        bytes(env.payload)
        require(env.originSignature.size == ORIGIN_SIG_LEN) { "originSig must be 64 bytes" }
        bytes(env.originSignature)
        require(env.hopSection.hopCount <= MAX_HOPS_WIRE)
        require(env.hopSection.breadcrumbs.size == env.hopSection.hopCount) { "breadcrumb count must equal hopCount" }
        u8(env.hopSection.hopCount)
        for (b in env.hopSection.breadcrumbs) {
            require(b.hopIdHash.size == 4) { "hopIdHash must be 4 bytes" }
            bytes(b.hopIdHash); u8(b.linkQuality); u8(b.loadClass)
        }
        require(env.hopSection.hopMac.size == HOP_MAC_LEN) { "hopMac must be 16 bytes" }
        bytes(env.hopSection.hopMac)
        return out.toByteArray()
    }

    /** Bytes covered by the origin signature: immutable header + payload (excludes the signature field itself). */
    fun immutableRegion(env: Envelope): ByteArray {
        val full = encode(env)
        val hopAndSigLen = 64 + (1 + env.hopSection.hopCount * BREADCRUMB_LEN + HOP_MAC_LEN)
        return full.copyOfRange(0, full.size - hopAndSigLen)
    }

    /** Bytes covered by the per-hop MAC: hopCount + breadcrumbs. */
    fun hopSectionRegion(env: Envelope): ByteArray {
        val full = encode(env)
        val macStart = full.size - HOP_MAC_LEN
        return full.copyOfRange(macStart - (1 + env.hopSection.hopCount * BREADCRUMB_LEN), macStart)
    }

    fun decode(data: ByteArray): DecodeResult {
        try {
            var p = 0
            fun need(n: Int) { if (p + n > data.size) throw MalformedException("truncated at offset $p needing $n") }
            fun u8(): Int { need(1); return data[p++].toInt() and 0xFF }
            fun u16(): Int { need(2); val v = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF); p += 2; return v }
            fun u32(): Int { need(4); val v = ((data[p].toInt() and 0xFF) shl 24) or ((data[p + 1].toInt() and 0xFF) shl 16) or ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF); p += 4; return v }
            fun take(n: Int): ByteArray { need(n); val b = data.copyOfRange(p, p + n); p += n; return b }

            val version = u8()
            if (version > PROTOCOL_VERSION) return DecodeResult.UnknownVersion(version)
            val typeId = u8()
            val type = MessageType.fromId(typeId) ?: return DecodeResult.Malformed("unknown type $typeId")
            val flags = u8()
            val msgId = take(16)
            val originId = take(8)
            val originEpoch = u32()
            val destKindId = u8()
            val destKind = DestKind.fromId(destKindId) ?: return DecodeResult.Malformed("unknown destKind $destKindId")
            var destId: ByteArray? = null
            if (destKind == DestKind.DRIVER_ID || destKind == DestKind.NODE) {
                destId = take(8)
            }
            val createdAt = u32()
            val ttl = u16()
            val maxHops = u8()
            val payloadLen = u16()
            if (payloadLen > MAX_PAYLOAD) return DecodeResult.Malformed("payloadLen $payloadLen exceeds cap")
            val payload = take(payloadLen)
            val originSig = take(ORIGIN_SIG_LEN)
            val hopCount = u8()
            if (hopCount > maxHops && maxHops != 0) return DecodeResult.Malformed("hopCount $hopCount > maxHops $maxHops on wire")
            val crumbs = ArrayList<Breadcrumb>(hopCount)
            repeat(hopCount) {
                val hash = take(4)
                val q = u8()
                val load = u8()
                crumbs.add(Breadcrumb(hash, q, load))
            }
            val hopMac = take(HOP_MAC_LEN)
            if (p != data.size) return DecodeResult.Malformed("trailing bytes: $p of ${data.size}")
            return DecodeResult.Ok(
                Envelope(version, type, flags, msgId, originId, originEpoch,
                    Destination(destKind, destId), createdAt, ttl, maxHops, payload, originSig,
                    HopSection(hopCount, crumbs, hopMac)),
            )
        } catch (e: MalformedException) {
            return DecodeResult.Malformed(e.reason)
        } catch (e: Exception) {
            // GATE 1: decode must never crash on hostile input
            return DecodeResult.Malformed("unexpected: ${e.javaClass.simpleName}")
        }
    }

    private class MalformedException(val reason: String) : Exception(reason)
}
