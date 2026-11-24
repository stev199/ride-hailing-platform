package com.ridehailing.mesh.bridge

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Stage 4 prep (P1B-5): the host sim-bridge wire format.
 *
 * Emulators run the app with [BridgeMeshTransport] (a real MeshTransport over a TCP
 * socket); the host bridge (GATE 4's "host sim bridge") connects all app instances
 * and relays frames according to the [TopologyEngine]. GATE 4 instrumented tests then
 * reproduce GATE 3 scenarios with REAL app code over REAL sockets, with only the radio
 * simulated.
 *
 * The bridge is deliberately CODEC-AGNOSTIC: it moves opaque envelope bytes (the app
 * encodes/decodes via mesh protocol Codec). The host never parses mesh envelopes and
 * never learns payload content - the same trust model as the BLE radio.
 *
 * Frame layout: [u8 type][u16 bodyLen][body]. Bodies:
 * - HELLO        {nodeId utf8}
 * - HELLO_OK     {} (server accepted the registration)
 * - BCAST        {envelope bytes}                       client -> host
 * - UNICAST      {u16 seq, nextHop utf8, envelope}      client -> host
 * - DELIVER      {from utf8, envelope bytes}            host -> client
 * - ACK          {u16 seq}                              host -> client (unicast delivered)
 * - NACK         {u16 seq, u8 reason}                   host -> client (1=unreachable)
 */
object SimBridgeFrames {

    const val TYPE_HELLO: Int = 1
    const val TYPE_HELLO_OK: Int = 2
    const val TYPE_BCAST: Int = 3
    const val TYPE_UNICAST: Int = 4
    const val TYPE_DELIVER: Int = 5
    const val TYPE_ACK: Int = 6
    const val TYPE_NACK: Int = 7

    const val NACK_UNREACHABLE: Int = 1

    const val MAX_FRAME_BYTES: Int = 512 * 1024 // fragmented envelopes are <= a few KB; generous ceiling

    /** Reads one frame; returns null on clean EOF. Throws [IOException] on malformed frames. */
    fun readFrame(input: DataInputStream): Frame? {
        val type = input.readUnsignedByte()
        val len = input.readUnsignedShort()
        if (len > MAX_FRAME_BYTES) throw IOException("frame too large: $len")
        val body = ByteArray(len)
        input.readFully(body)
        return Frame(type, body)
    }

    fun writeFrame(out: DataOutputStream, type: Int, body: ByteArray) {
        require(body.size <= MAX_FRAME_BYTES) { "frame too large: ${body.size}" }
        out.writeByte(type)
        out.writeShort(body.size)
        out.write(body)
        out.flush()
    }

    data class Frame(val type: Int, val body: ByteArray)

    // ---- body builders/parsers (tiny, hand-rolled, zero-dependency per charter) ----

    fun helloBody(nodeId: String): ByteArray = nodeId.toByteArray(Charsets.UTF_8)

    fun parseHello(body: ByteArray): String = String(body, Charsets.UTF_8)

    fun unicastBody(seq: Int, nextHop: String, envelope: ByteArray): ByteArray {
        val hop = nextHop.toByteArray(Charsets.UTF_8)
        val buf = ByteArrayOutputStream(4 + hop.size + envelope.size)
        DataOutputStream(buf).use { d ->
            d.writeShort(seq)
            d.writeShort(hop.size)
            d.write(hop)
            d.write(envelope)
        }
        return buf.toByteArray()
    }

    data class Unicast(val seq: Int, val nextHop: String, val envelope: ByteArray)

    fun parseUnicast(body: ByteArray): Unicast {
        val d = DataInputStream(body.inputStream())
        val seq = d.readUnsignedShort()
        val hopLen = d.readUnsignedShort()
        val hop = ByteArray(hopLen).also { d.readFully(it) }
        val envelope = ByteArray(body.size - 4 - hopLen).also { d.readFully(it) }
        return Unicast(seq, String(hop, Charsets.UTF_8), envelope)
    }

    fun deliverBody(from: String, envelope: ByteArray): ByteArray {
        val fromBytes = from.toByteArray(Charsets.UTF_8)
        val buf = ByteArrayOutputStream(2 + fromBytes.size + envelope.size)
        DataOutputStream(buf).use { d ->
            d.writeShort(fromBytes.size)
            d.write(fromBytes)
            d.write(envelope)
        }
        return buf.toByteArray()
    }

    data class Deliver(val from: String, val envelope: ByteArray)

    fun parseDeliver(body: ByteArray): Deliver {
        val d = DataInputStream(body.inputStream())
        val fromLen = d.readUnsignedShort()
        val from = ByteArray(fromLen).also { d.readFully(it) }
        val envelope = ByteArray(body.size - 2 - fromLen).also { d.readFully(it) }
        return Deliver(String(from, Charsets.UTF_8), envelope)
    }

    fun ackBody(seq: Int): ByteArray = byteArrayOf((seq shr 8).toByte(), seq.toByte())

    fun parseSeq(body: ByteArray): Int =
        ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)

    fun nackBody(seq: Int, reason: Int): ByteArray =
        byteArrayOf((seq shr 8).toByte(), seq.toByte(), reason.toByte())

    fun parseNack(body: ByteArray): Pair<Int, Int> = Pair(parseSeq(body), body[2].toInt() and 0xff)
}
