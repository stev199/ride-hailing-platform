package com.ridehailing.mesh.app

import com.ridehailing.mesh.crypto.MeshCrypto
import com.ridehailing.mesh.protocol.Codec
import com.ridehailing.mesh.protocol.DecodeResult
import com.ridehailing.mesh.protocol.DestKind
import com.ridehailing.mesh.protocol.Destination
import com.ridehailing.mesh.protocol.Flags
import com.ridehailing.mesh.protocol.MessageType
import com.ridehailing.mesh.protocol.Validator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GATE 2 close-out tests (Part C Stage 2): beacons, driver certificates, presence
 * sealing, gateway opacity, internet-shortcut preference. All byte layouts are
 * deterministic; hostile input only ever produces sealed-class results.
 */
class Gate2AppTest {

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun backendKeys() = MeshCrypto.newIdentity()
    private fun driverKeys() = MeshCrypto.newIdentity()
    private fun profileKeys() = MeshCrypto.newIdentity()

    private fun sampleProfile(driverId: ByteArray, pKey: java.security.KeyPair, nowSec: Int) =
        DriverCredentials.signSnippet(
            ProfileSnippet(
                driverId, "Karim B.", 47, "Toyota Corolla, white",
                MeshCrypto.sha256("photo-1".toByteArray()).copyOf(8), nowSec,
                ByteArray(64),
            ),
            pKey,
        )

    // ---------------- beacon codec ----------------

    @Test
    fun `beacon roundtrip - fields quantized position profile and mac`() {
        val dKey = profileKeys()
        val driverId = MeshCrypto.sha256("driver-1".toByteArray()).copyOf(8)
        val macKey = DriverCredentials.beaconMacKey(dKey, driverId)
        val profile = sampleProfile(driverId, dKey, 1_700_000_000).canonicalBytes()

        val bytes = BeaconCodec.encode(
            rotatingId = MeshCrypto.rotatingOriginId("dev-secret".toByteArray(), 3),
            latMeters = 123.4, lonMeters = -77.6,
            rideTypeBitmap = 0b101, available = true, keyEpoch = 7,
            macKey = macKey, profile = profile,
        )
        val res = BeaconCodec.decode(bytes, macKey)
        assertIs<BeaconDecode.Ok>(res)
        assertTrue(res.macVerified)
        // ~10 m grid: 123.4 -> 12 (12*10=120 m, within one grid cell of the truth)
        assertEquals(12, res.payload.latQuantized)
        assertEquals(-8, res.payload.lonQuantized)
        assertEquals(0b101, res.payload.rideTypeBitmap)
        assertTrue(res.payload.available)
        assertEquals(7, res.payload.keyEpoch)
        assertNotEquals(0, res.payload.profile?.size)
        assertEquals(8, res.payload.shortMac.size)
        assertEquals(8, res.payload.rotatingId.size)
    }

    @Test
    fun `beacon without profile decodes with null profile`() {
        val dKey = profileKeys()
        val driverId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val bytes = BeaconCodec.encode(driverId, 0.0, 0.0, 1, false, 1, DriverCredentials.beaconMacKey(dKey, driverId))
        val res = BeaconCodec.decode(bytes, null)
        assertIs<BeaconDecode.Ok>(res)
        assertEquals(false, res.macVerified) // mac key not held -> deferred to envelope signature
        assertNull(res.payload.profile)
    }

    @Test
    fun `beacon tampered body fails the short mac`() {
        val dKey = profileKeys()
        val driverId = MeshCrypto.sha256("driver-2".toByteArray()).copyOf(8)
        val macKey = DriverCredentials.beaconMacKey(dKey, driverId)
        val bytes = BeaconCodec.encode(driverId, 10.0, 20.0, 0, true, 1, macKey)
        bytes[9] = (bytes[9].toInt() xor 0x01).toByte() // flip a position bit
        val res = BeaconCodec.decode(bytes, macKey)
        assertIs<BeaconDecode.MacInvalid>(res)
    }

    @Test
    fun `beacon decode never crashes on hostile input - 3000 fuzz rounds`() {
        val rng = java.util.Random(42)
        repeat(3000) {
            val len = rng.nextInt(80)
            val data = ByteArray(len)
            rng.nextBytes(data)
            val res = BeaconCodec.decode(data, byteArrayOf(9, 9))
            assertTrue(res is BeaconDecode.Malformed || res is BeaconDecode.MacInvalid || res is BeaconDecode.Ok)
        }
    }

    @Test
    fun `beacon encode rejects oversized rotating id`() {
        assertFailsWith<IllegalArgumentException> {
            BeaconCodec.encode(ByteArray(9), 0.0, 0.0, 0, true, 1, ByteArray(32))
        }
    }

    // ---------------- driver certificates + profile snippets ----------------

    @Test
    fun `certificate chain verifies - backend signs cert cert key signs profile`() {
        val backend = backendKeys()
        val dKey = profileKeys()
        val driverId = MeshCrypto.sha256("driver-3".toByteArray()).copyOf(8)
        val cert = DriverCredentials.issueCertificate(driverId, dKey, 1_700_000_000, 1_700_100_000, backend)
        val snippet = sampleProfile(driverId, dKey, 1_700_000_100)
        assertEquals(ProfileVerdict.Ok, DriverCredentials.verify(cert, snippet, backend.public, 1_700_000_200))
    }

    @Test
    fun `certificate not signed by the backend is rejected`() {
        val backend = backendKeys()
        val imposter = backendKeys() // not the pinned backend key
        val dKey = profileKeys()
        val driverId = MeshCrypto.sha256("driver-4".toByteArray()).copyOf(8)
        val cert = DriverCredentials.issueCertificate(driverId, dKey, 1_700_000_000, 1_700_100_000, imposter)
        val snippet = sampleProfile(driverId, dKey, 1_700_000_100)
        val v = DriverCredentials.verify(cert, snippet, backend.public, 1_700_000_200)
        assertEquals("bad backend signature", (v as ProfileVerdict.Rejected).reason)
    }

    @Test
    fun `expired certificate is rejected`() {
        val backend = backendKeys()
        val dKey = profileKeys()
        val driverId = MeshCrypto.sha256("driver-5".toByteArray()).copyOf(8)
        val cert = DriverCredentials.issueCertificate(driverId, dKey, 1_700_000_000, 1_700_050_000, backend)
        val snippet = sampleProfile(driverId, dKey, 1_700_000_100)
        assertEquals("certificate expired", (DriverCredentials.verify(cert, snippet, backend.public, 1_700_050_001) as ProfileVerdict.Rejected).reason)
    }

    @Test
    fun `profile signed by a foreign key is rejected`() {
        val backend = backendKeys()
        val dKey = profileKeys()
        val rogue = profileKeys()
        val driverId = MeshCrypto.sha256("driver-6".toByteArray()).copyOf(8)
        val cert = DriverCredentials.issueCertificate(driverId, dKey, 1_700_000_000, 1_700_100_000, backend)
        val forged = DriverCredentials.signSnippet(
            ProfileSnippet(driverId, "Fake", 50, "car", ByteArray(8), 1_700_000_100, ByteArray(64)),
            rogue,
        )
        assertEquals("bad profile signature", (DriverCredentials.verify(cert, forged, backend.public, 1_700_000_200) as ProfileVerdict.Rejected).reason)
    }

    @Test
    fun `snippet driver id must match the certificate`() {
        val backend = backendKeys()
        val dKey = profileKeys()
        val cert = DriverCredentials.issueCertificate(
            MeshCrypto.sha256("driver-a".toByteArray()).copyOf(8), dKey, 0, 1_700_100_000, backend,
        )
        val otherId = MeshCrypto.sha256("driver-b".toByteArray()).copyOf(8)
        val snippet = sampleProfile(otherId, dKey, 100)
        assertEquals("driver id mismatch", (DriverCredentials.verify(cert, snippet, backend.public, 200) as ProfileVerdict.Rejected).reason)
    }

    @Test
    fun `beacon mac key is deterministic per driver and key`() {
        val dKey = profileKeys()
        val id1 = MeshCrypto.sha256("d1".toByteArray()).copyOf(8)
        val id2 = MeshCrypto.sha256("d2".toByteArray()).copyOf(8)
        assertEquals(hex(DriverCredentials.beaconMacKey(dKey, id1)), hex(DriverCredentials.beaconMacKey(dKey, id1)))
        assertNotEquals(hex(DriverCredentials.beaconMacKey(dKey, id1)), hex(DriverCredentials.beaconMacKey(dKey, id2)))
    }

    // ---------------- ledger ----------------

    @Test
    fun `ledger entries expire and profiles honor the relay-once rule`() {
        var now = 0L
        val ledger = BeaconLedger { now }
        val rot = MeshCrypto.sha256("rot-1".toByteArray()).copyOf(8)
        val driverId = MeshCrypto.sha256("drv-1".toByteArray()).copyOf(8)
        val payload = BeaconPayload(rot, 5, 6, 1, true, 1, null, ByteArray(8))

        ledger.observe(payload, fromNeighbor = "rel1")
        assertEquals(1, ledger.nearby().size)
        now = 29_999
        assertEquals(1, ledger.nearby(now).size)
        now = 30_001
        assertEquals(0, ledger.nearby(now).size) // expired silently dropped

        assertTrue(ledger.shouldRelayProfile(driverId)) // relayed once...
        assertEquals(false, ledger.shouldRelayProfile(driverId)) // ...then suppressed
        val snippet = sampleProfile(driverId, profileKeys(), 100)
        ledger.cacheProfile(snippet) // cached at now=30001, expires at 330001
        assertEquals(snippet.displayName, ledger.profileFor(driverId)?.displayName)
        now = 330_002
        assertNull(ledger.profileFor(driverId, now)) // cache expiry
        assertEquals(true, ledger.shouldRelayProfile(driverId, now)) // new generation allows relay again
    }

    // ---------------- adaptive scheduler ----------------

    @Test
    fun `scheduler uses base interval when sparse and grows when dense capped at max`() {
        var now = 0L
        val s = BeaconScheduler({ now }, baseIntervalSec = 5, maxIntervalSec = 15, denseThreshold = 6)
        assertEquals(5, s.intervalSec(1))
        assertEquals(5, s.intervalSec(6))
        assertEquals(7, s.intervalSec(8)) // 5 * (1 + 2*0.25) = 7.5 truncated
        assertEquals(15, s.intervalSec(50)) // capped

        // emission cadence: sparse -> 5 s, dense -> stretched
        var sparse = 0
        now = 0
        repeat(60) { if (s.shouldEmit(1, now * 1000)) sparse++; now++ } // 60 ticks of 1 s
        assertEquals(12, sparse)

        var dense = 0
        now = 0
        val denseS = BeaconScheduler({ now }, baseIntervalSec = 5, maxIntervalSec = 15, denseThreshold = 6)
        repeat(60) { if (denseS.shouldEmit(14, now * 1000)) dense++; now++ }
        assertTrue(dense in 1..8, "dense world must beacon less: $dense")
        assertTrue(dense < sparse)
    }

    // ---------------- presence ----------------

    @Test
    fun `presence roundtrip through the backend key - coarse grid`() {
        val backend = MeshCrypto.newX25519KeyPair()
        val rot = MeshCrypto.sha256("rider-1".toByteArray()).copyOf(8)
        val sealed = PresenceCodec.seal(rot, 456.7, -890.1, 1_700_000_000, backend.public)
        val opened = PresenceCodec.open(sealed, backend.private)
        assertTrue(opened != null)
        assertEquals(hex(rot), hex(opened!!.rotatingId))
        assertEquals(5, opened.latQuantized) // 456.7 -> 5 (100 m grid)
        assertEquals(-9, opened.lonQuantized) // -890.1 -> -9
        assertEquals(1_700_000_000, opened.createdAtSec)
    }

    @Test
    fun `presence cannot be opened by a gateway key - backend only`() {
        val backend = MeshCrypto.newX25519KeyPair()
        val gateway = MeshCrypto.newX25519KeyPair()
        val rot = MeshCrypto.sha256("rider-2".toByteArray()).copyOf(8)
        val sealed = PresenceCodec.seal(rot, 10.0, 20.0, 1000, backend.public)
        assertNull(PresenceCodec.open(sealed, gateway.private), "a gateway key must never open presence")
        val tampered = sealed.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        assertNull(PresenceCodec.open(tampered, backend.private))
    }

    // ---------------- gateway announce + internet shortcut ----------------

    @Test
    fun `gateway announce roundtrip and clock-sync compatibility`() {
        val bytes = GatewayAnnounceCodec.encode(GatewayAnnounceCodec.Announce(1_700_000_123, GatewayAnnounceCodec.CAP_INTERNET, 5))
        // Existing clock-sync contract: payload[0..3] is the backend-signed clock seconds.
        val clockSec = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        assertEquals(1_700_000_123, clockSec)
        val a = GatewayAnnounceCodec.decode(bytes)
        assertEquals(GatewayAnnounceCodec.CAP_INTERNET, a?.capabilities)
        assertEquals(5, a?.beaconIntervalSec)
        assertNull(GatewayAnnounceCodec.decode(byteArrayOf(1, 2)))
    }

    @Test
    fun `internet shortcut - fewest hops wins when scores within 10 percent`() {
        val withNet = true
        assertTrue(InternetShortcut.preferGateway(meshPathCost = 10.0, meshPathHops = 3, gatewayPathCost = 10.5, gatewayPathHops = 1, gatewayHasInternet = withNet))
        assertTrue(InternetShortcut.preferGateway(meshPathCost = 10.0, meshPathHops = 2, gatewayPathCost = 11.0, gatewayPathHops = 1, gatewayHasInternet = withNet), "exactly 10% is within tolerance")
        assertEquals(false, InternetShortcut.preferGateway(10.0, 3, 11.1, 1, withNet), ">10% cost penalty loses")
        assertEquals(false, InternetShortcut.preferGateway(10.0, 3, 10.5, 3, withNet), "same hop count: no shortcut")
        assertEquals(false, InternetShortcut.preferGateway(10.0, 3, 1.0, 1, gatewayHasInternet = false))
    }

    // ---------------- gateway relay service (opacity) ----------------

    /** Fake backend link: captures what the gateway sent, replies with a backend-signed QUOTE. */
    private class FakeBackend(private val backendIdentity: java.security.KeyPair, val riderOriginId: ByteArray) : GatewayLink {
        val forwarded = mutableListOf<Pair<ByteArray, ByteArray>>() // bytes to gatewayId
        var reject = false

        override fun forward(requestEnvelopeBytes: ByteArray, gatewayId: ByteArray): ByteArray? {
            forwarded.add(requestEnvelopeBytes to gatewayId)
            if (reject) return null
            val quote = Validator.buildEnvelope(
                type = MessageType.QUOTE,
                flags = Flags.CRITICAL,
                destination = Destination(DestKind.NODE, riderOriginId),
                payload = "quote|backend".toByteArray(),
                maxHops = 3, ttlSec = 30,
                identity = backendIdentity,
                originId = MeshCrypto.sha256("backend".toByteArray()).copyOf(8),
                originEpoch = 1,
                createdAtSec = 1_700_000_000,
            )
            return Codec.encode(quote)
        }
    }

    private fun gatewayRequestEnvelope(rider: java.security.KeyPair, riderOriginId: ByteArray): com.ridehailing.mesh.protocol.Envelope =
        Validator.buildEnvelope(
            type = MessageType.RIDE_REQUEST,
            flags = Flags.ACK_REQUIRED or Flags.ENCRYPTED,
            destination = Destination(DestKind.GATEWAY_ANY),
            payload = "sealed-e2e-blob-not-readable-by-gateway".toByteArray(),
            maxHops = 3, ttlSec = 120,
            identity = rider,
            originId = riderOriginId,
            originEpoch = 1,
            createdAtSec = 1_700_000_000,
        )

    @Test
    fun `gateway forwards opaquely - original signature and payload bytes intact`() {
        val rider = MeshCrypto.newIdentity()
        val riderOriginId = MeshCrypto.sha256("rider-op".toByteArray()).copyOf(8)
        val gwIdentity = MeshCrypto.newIdentity()
        val gwId = MeshCrypto.sha256("gateway-1".toByteArray()).copyOf(8)
        val backendLink = FakeBackend(MeshCrypto.newIdentity(), riderOriginId)
        val injected = mutableListOf<com.ridehailing.mesh.protocol.Envelope>()
        val svc = GatewayRelayService(gwId, gwIdentity, backendLink, { injected.add(it) }, { 1_700_000_005 })

        val request = gatewayRequestEnvelope(rider, riderOriginId)
        val res = svc.onEnvelope(request)
        assertEquals(GatewayRelayResult.Forwarded, res)

        // Opaque: the gateway forwarded the ORIGINAL encoded bytes - byte-for-byte.
        val (sentBytes, sentGwId) = backendLink.forwarded.single()
        assertEquals(hex(gwId), hex(sentGwId))
        assertEquals(hex(Codec.encode(request)), hex(sentBytes))
        val decoded = Codec.decode(sentBytes)
        assertIs<DecodeResult.Ok>(decoded)
        assertEquals(hex(request.originSignature), hex(decoded.envelope.originSignature), "signature must be untouched")
        assertEquals(hex(request.payload), hex(decoded.envelope.payload), "payload must be untouched (E2E)")
        // The gateway never learns identity: the payload never contains plaintext it could read.
        assertTrue(!String(decoded.envelope.payload).contains("rider"))
    }

    @Test
    fun `backend response reaches the rider wrapped in GATEWAY_RELAY - unwrap restores the inner envelope`() {
        val riderOriginId = MeshCrypto.sha256("rider-unwrap".toByteArray()).copyOf(8)
        val gwIdentity = MeshCrypto.newIdentity()
        val gwId = MeshCrypto.sha256("gateway-2".toByteArray()).copyOf(8)
        val backendIdentity = MeshCrypto.newIdentity()
        val backendLink = FakeBackend(backendIdentity, riderOriginId)
        val injected = mutableListOf<com.ridehailing.mesh.protocol.Envelope>()
        val svc = GatewayRelayService(gwId, gwIdentity, backendLink, { injected.add(it) }, { 1_700_000_005 })

        val rider = MeshCrypto.newIdentity()
        svc.onEnvelope(gatewayRequestEnvelope(rider, riderOriginId))

        // The gateway injected exactly one GATEWAY_RELAY wrapper addressed to the rider.
        val wrapper = injected.single()
        assertEquals(MessageType.GATEWAY_RELAY, wrapper.type)
        assertEquals(DestKind.NODE, wrapper.destination.kind)
        assertEquals(hex(riderOriginId), hex(wrapper.destination.id!!))
        assertEquals(hex(gwId), hex(wrapper.originId))

        // Rider-side unwrap: the inner envelope is the backend's QUOTE, signature intact.
        val inner = svc.unwrap(wrapper.payload)
        assertTrue(inner != null)
        assertEquals(MessageType.QUOTE, inner!!.type)
        assertEquals("quote|backend", String(inner.payload))
        val backendPubOk = MeshCrypto.verify(
            backendIdentity.public,
            Codec.immutableRegion(inner),
            inner.originSignature,
        )
        assertTrue(backendPubOk, "the backend's origin signature must survive the gateway verbatim")
    }

    @Test
    fun `unwrap rejects truncated payload and tampered gateway tag`() {
        val gwIdentity = MeshCrypto.newIdentity()
        val gwId = MeshCrypto.sha256("gateway-3".toByteArray()).copyOf(8)
        val backendLink = FakeBackend(MeshCrypto.newIdentity(), ByteArray(8))
        val injected = mutableListOf<com.ridehailing.mesh.protocol.Envelope>()
        val svc = GatewayRelayService(gwId, gwIdentity, backendLink, { injected.add(it) }, { 0 })

        val riderOriginId = MeshCrypto.sha256("rider-t".toByteArray()).copyOf(8)
        val rider = MeshCrypto.newIdentity()
        svc.onEnvelope(gatewayRequestEnvelope(rider, riderOriginId))
        val payload = injected.single().payload

        assertNull(svc.unwrap(payload.copyOfRange(0, 10)), "truncated -> null")
        val tampered = payload.copyOf().also { it[payload.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(svc.unwrap(tampered), "tampered gwTag -> null")
        // full payload still unwraps
        assertTrue(svc.unwrap(payload) != null)
    }

    @Test
    fun `non relayable envelopes are ignored by the gateway service`() {
        val gwIdentity = MeshCrypto.newIdentity()
        val gwId = MeshCrypto.sha256("gateway-4".toByteArray()).copyOf(8)
        val backendLink = FakeBackend(MeshCrypto.newIdentity(), ByteArray(8))
        val injected = mutableListOf<com.ridehailing.mesh.protocol.Envelope>()
        val svc = GatewayRelayService(gwId, gwIdentity, backendLink, { injected.add(it) }, { 0 })

        val rider = MeshCrypto.newIdentity()
        // QUOTE to a node: not relayable
        val quote = Validator.buildEnvelope(
            type = MessageType.QUOTE, flags = Flags.CRITICAL,
            destination = Destination(DestKind.NODE, gwId), payload = "q".toByteArray(),
            maxHops = 3, ttlSec = 30, identity = rider,
            originId = MeshCrypto.sha256("r".toByteArray()).copyOf(8), originEpoch = 1, createdAtSec = 0,
        )
        assertEquals(GatewayRelayResult.NotRelayable, svc.onEnvelope(quote))
        // RIDE_REQUEST but addressed DRIVER_ANY: not for the gateway
        val wrongDest = Validator.buildEnvelope(
            type = MessageType.RIDE_REQUEST, flags = 0,
            destination = Destination(DestKind.DRIVER_ANY), payload = "q".toByteArray(),
            maxHops = 3, ttlSec = 30, identity = rider,
            originId = MeshCrypto.sha256("r".toByteArray()).copyOf(8), originEpoch = 1, createdAtSec = 0,
        )
        assertEquals(GatewayRelayResult.NotRelayable, svc.onEnvelope(wrongDest))
        assertTrue(injected.isEmpty())
        assertTrue(backendLink.forwarded.isEmpty())
    }

    @Test
    fun `backend rejection yields BackendRejected and injects nothing`() {
        val gwIdentity = MeshCrypto.newIdentity()
        val gwId = MeshCrypto.sha256("gateway-5".toByteArray()).copyOf(8)
        val backendLink = FakeBackend(MeshCrypto.newIdentity(), ByteArray(8)).apply { reject = true }
        val injected = mutableListOf<com.ridehailing.mesh.protocol.Envelope>()
        val svc = GatewayRelayService(gwId, gwIdentity, backendLink, { injected.add(it) }, { 0 })
        val rider = MeshCrypto.newIdentity()
        val res = svc.onEnvelope(
            gatewayRequestEnvelope(rider, MeshCrypto.sha256("rider-rej".toByteArray()).copyOf(8)),
        )
        assertEquals(GatewayRelayResult.BackendRejected, res)
        assertTrue(injected.isEmpty())
    }
}
