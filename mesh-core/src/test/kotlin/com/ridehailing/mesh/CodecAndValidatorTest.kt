package com.ridehailing.mesh

import com.ridehailing.mesh.crypto.MeshCrypto
import com.ridehailing.mesh.protocol.Breadcrumb
import com.ridehailing.mesh.protocol.Codec
import com.ridehailing.mesh.protocol.DecodeResult
import com.ridehailing.mesh.protocol.DestKind
import com.ridehailing.mesh.protocol.Destination
import com.ridehailing.mesh.protocol.Envelope
import com.ridehailing.mesh.protocol.Flags
import com.ridehailing.mesh.protocol.HopSection
import com.ridehailing.mesh.protocol.MessageType
import com.ridehailing.mesh.protocol.PROTOCOL_VERSION
import com.ridehailing.mesh.protocol.Validator
import java.security.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CodecAndValidatorTest {

    private val identity: KeyPair = MeshCrypto.newIdentity()
    private val originId = ByteArray(8) { it.toByte() }
    private val msgId = ByteArray(16) { (it * 3).toByte() }

    private fun envelope(hopCount: Int = 0, ttl: Int = 120, createdAt: Int = 1_800_000_000): Envelope {
        val base = Validator.buildEnvelope(
            type = MessageType.RIDE_REQUEST,
            flags = Flags.ENCRYPTED or Flags.CRITICAL,
            destination = Destination(DestKind.DRIVER_ID, ByteArray(8) { (it + 1).toByte() }),
            payload = ByteArray(64) { (it * 7).toByte() },
            maxHops = 3,
            ttlSec = ttl,
            identity = identity,
            originId = originId,
            originEpoch = 42,
            createdAtSec = createdAt,
        )
        if (hopCount == 0) return base
        val crumbs = (0 until hopCount).map { Breadcrumb(ByteArray(4) { it.toByte() }, 200, 1) }
        val withHops = base.copy(hopSection = HopSection(hopCount, crumbs, ByteArray(16)))
        val mac = MeshCrypto.hopMac(ByteArray(32) { 9 }, Codec.hopSectionRegion(withHops))
        return withHops.copy(hopSection = withHops.hopSection.copy(hopMac = mac))
    }

    @Test
    fun `roundtrip preserves all fields`() {
        val env = envelope(hopCount = 2)
        val bytes = Codec.encode(env)
        when (val r = Codec.decode(bytes)) {
            is DecodeResult.Ok -> {
                assertEquals(env.type, r.envelope.type)
                assertEquals(env.flags, r.envelope.flags)
                assertTrue(env.msgId.contentEquals(r.envelope.msgId))
                assertTrue(env.originId.contentEquals(r.envelope.originId))
                assertEquals(env.originEpoch, r.envelope.originEpoch)
                assertEquals(env.destination.kind, r.envelope.destination.kind)
                assertEquals(env.maxHops, r.envelope.maxHops)
                assertEquals(env.payload.size, r.envelope.payload.size)
                assertEquals(env.hopSection.hopCount, r.envelope.hopSection.hopCount)
                assertTrue(env.hopSection.hopMac.contentEquals(r.envelope.hopSection.hopMac))
            }
            else -> throw AssertionError("expected Ok, got $r")
        }
    }

    @Test
    fun `signature verifies over immutable region and breaks on bit flip`() {
        val env = envelope()
        val pub = MeshCrypto.ed25519PublicKeyFromRaw(MeshCrypto.rawEd25519(identity.public))
        assertEquals(Validator.Verdict.Valid, Validator.validate(env, 3, env.createdAtSec + 10, pub))
        val bytes = Codec.encode(env)
        // flip every bit of the immutable header region: signature or structure must reject
        val immutableLen = bytes.size - (1 + env.hopSection.hopCount * 6 + 16)
        var rejected = 0
        for (i in 0 until immutableLen) {
            for (bit in 0 until 8) {
                val mutated = bytes.clone()
                mutated[i] = (mutated[i].toInt() xor (1 shl bit)).toByte()
                val decoded = Codec.decode(mutated)
                val verdict = when (decoded) {
                    is DecodeResult.Ok ->
                        Validator.validate(decoded.envelope, 3, env.createdAtSec + 10, pub)
                    else -> Validator.Verdict.Invalid("malformed")
                }
                assertIs<Validator.Verdict.Invalid>(verdict, "bit $bit of byte $i was accepted")
                rejected++
            }
        }
        assertTrue(rejected > 500)
    }

    @Test
    fun `expired ttl and hop overflow reject`() {
        val env = envelope(ttl = 10, createdAt = 1_800_000_000)
        assertIs<Validator.Verdict.Invalid>(
            Validator.validate(env, 3, env.createdAtSec + 11, null),
        )
        val hop4 = envelope(hopCount = 4) // maxHops=3 on wire
        assertIs<Validator.Verdict.Invalid>(Validator.validate(hop4, 3, 1_800_000_001, null))
    }

    @Test
    fun `relay increments hop and appends breadcrumb, enforces budget`() {
        val env = envelope()
        val forwarded = Validator.relay(
            env, 3, env.createdAtSec + 1, ByteArray(4) { 7 }, 200, 1, ByteArray(32) { 5 }, null,
        )
        assertIs<Validator.RelayResult.Forwarded>(forwarded)
        assertEquals(1, forwarded.envelope.hopSection.hopCount)
        assertNotEquals(env.hopSection.hopMac, forwarded.envelope.hopSection.hopMac)
        // exhaust budget: maxHops=3 -> relaying 3 times then drops
        var cur = env
        repeat(3) { cur = assertIs<Validator.RelayResult.Forwarded>(Validator.relay(cur, 3, env.createdAtSec + 1, ByteArray(4) { 7 }, 200, 1, ByteArray(32) { 5 }, null)).envelope }
        assertIs<Validator.RelayResult.Dropped>(
            Validator.relay(cur, 3, env.createdAtSec + 1, ByteArray(4) { 7 }, 200, 1, ByteArray(32) { 5 }, null),
        )
    }

    @Test
    fun `unknown version is reported gracefully, never crashes`() {
        val env = envelope().copy(version = PROTOCOL_VERSION + 1)
        assertIs<DecodeResult.UnknownVersion>(Codec.decode(Codec.encode(env)))
    }

    @Test
    fun `fuzz - malformed truncated oversized inputs never crash decode`() {
        val valid = Codec.encode(envelope(hopCount = 2))
        val rng = java.util.Random(1234)
        // truncations
        for (len in 0 until valid.size) {
            assertIsNotDecodeCrash(valid.copyOf(len))
        }
        // random garbage
        repeat(2000) {
            val garbage = ByteArray(rng.nextInt(300)); rng.nextBytes(garbage)
            assertIsNotDecodeCrash(garbage)
        }
        // mutations of a valid envelope
        repeat(2000) {
            val mutated = valid.clone()
            mutated[rng.nextInt(mutated.size)] = rng.nextInt(256).toByte()
            assertIsNotDecodeCrash(mutated)
        }
        // oversized payloadLen header with short body
        val big = valid.clone(); big[0] = 1; big[big.size - 80] = 0x0F.toByte(); big[big.size - 79] = 0xFF.toByte()
        assertIsNotDecodeCrash(big)
    }

    private fun assertIsNotDecodeCrash(bytes: ByteArray) {
        when (val r = Codec.decode(bytes)) {
            is DecodeResult.Ok, is DecodeResult.Malformed, is DecodeResult.UnknownVersion -> {}
        }
    }
}
