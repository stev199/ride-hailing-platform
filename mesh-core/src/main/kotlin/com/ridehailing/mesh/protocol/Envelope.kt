package com.ridehailing.mesh.protocol

/**
 * Part C Stage 1 protocol: wire envelope, message types, flags.
 *
 * Byte layout (BIG-ENDIAN throughout, documented in docs/MESH_PROTOCOL.md when Stage 7 lands):
 *
 *   ver u8 | type u8 | flags u8 | msgId 16B | originId 8B | originEpoch u32 |
 *   destKind u8 | destId 8B (only for DRIVER_ID / NODE) | createdAt u32 | ttlSec u16 |
 *   maxHops u8 | payloadLen u16 | payload (payloadLen B) | originSig 64B |
 *   hopCount u8 | breadcrumbs (hopCount x 6B) | hopMac 16B
 *
 * The origin signature covers everything up to and including the payload (immutable
 * header + E2E ciphertext). Relays may only touch the hop section (hopCount,
 * breadcrumbs, hopMac). Unknown version -> UnknownVersion, never a crash.
 */
enum class MessageType(val id: Int) {
    DRIVER_BEACON(1),
    RIDE_REQUEST(2),
    REQUEST_ACK(3),
    QUOTE(4),
    QUOTE_ACCEPT(5),
    QUOTE_DECLINE(6),
    DRIVER_OFFER(7),
    TRIP_UPDATE(8),
    PRESENCE(9),
    GATEWAY_ANNOUNCE(10),
    GATEWAY_RELAY(11),
    ROUTE_REQUEST(12),
    ROUTE_REPLY(13),
    ROUTE_ERROR(14),
    HOP_ACK(15),
    PING(16),
    CONFIG_SYNC(17),
    REVOCATION_SYNC(18);

    companion object {
        fun fromId(id: Int): MessageType? = entries.firstOrNull { it.id == id }
    }
}

enum class DestKind(val id: Int) {
    ANY(0),
    DRIVER_ANY(1),
    DRIVER_ID(2),
    GATEWAY_ANY(3),
    NODE(4);

    companion object {
        fun fromId(id: Int): DestKind? = entries.firstOrNull { it.id == id }
    }
}

object Flags {
    const val ACK_REQUIRED: Int = 0x01
    const val CRITICAL: Int = 0x02
    const val FRAGMENT: Int = 0x04
    const val ENCRYPTED: Int = 0x08
}

const val PROTOCOL_VERSION: Int = 1

data class Destination(val kind: DestKind, val id: ByteArray? = null)

/** Per-hop breadcrumb appended by each relay: 4B hashed hop id + 1B link quality + 1B load class. */
data class Breadcrumb(val hopIdHash: ByteArray, val linkQuality: Int, val loadClass: Int)

data class HopSection(
    val hopCount: Int,
    val breadcrumbs: List<Breadcrumb>,
    val hopMac: ByteArray,
)

data class Envelope(
    val version: Int,
    val type: MessageType,
    val flags: Int,
    val msgId: ByteArray,
    val originId: ByteArray,
    val originEpoch: Int,
    val destination: Destination,
    val createdAtSec: Int,
    val ttlSec: Int,
    val maxHops: Int,
    val payload: ByteArray,
    val originSignature: ByteArray,
    val hopSection: HopSection,
)
