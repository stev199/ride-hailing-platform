package com.ridehailing.mesh.app

import com.ridehailing.mesh.crypto.MeshCrypto
import java.security.PrivateKey
import java.security.PublicKey

/**
 * GATE 2 close-out (Part C Stage 2, spec "Beacons"): DRIVER_BEACON + PRESENCE.
 *
 * DRIVER_BEACON every beaconIntervalSec (default 5 s, admin-configurable via
 * MeshConfig, adaptive when many nodes are heard): rotating ID, quantized
 * position (~10 m grid), ride-type bitmap, availability flag, key epoch, short
 * MAC. The signed profile snippet (name, rating, vehicle summary, photo hash) is
 * relayed once and cached with expiry; it verifies against backend-issued driver
 * certificates.
 *
 * PRESENCE from offline riders (coarse position, rotating ID) is relayed to
 * gateways every presenceIntervalSec (default 30 s), encrypted to the BACKEND
 * only (anonymous sealed box - gateways and relays can learn nothing).
 *
 * This package is pure Kotlin: no Android imports, no backend dependency
 * (module charter, D56/D57).
 *
 * DRIVER_BEACON payload layout (BIG-ENDIAN):
 *   rotatingId 8B | latQ i32 | lonQ i32 | rideTypeBitmap u8 | availability u8 |
 *   keyEpoch u32 | profileLen u16 | profile (profileLen B, 0 = absent) | shortMac 8B
 *
 * shortMac = HMAC-SHA256(beaconMacKey, everything before the mac), truncated to
 * 8 bytes. beaconMacKey is derived from the driver's certified profile-signing
 * key, so any node holding the cached certificate verifies it (cheap tamper
 * check before the full envelope signature is checked; the envelope's origin
 * signature remains the cryptographic anchor).
 */

/** ~10 m grid in the sim's local meter coordinate system (quantized position). */
const val BEACON_POSITION_GRID_M: Double = 10.0

/** PRESENCE coarse grid (~100 m) - offline riders share LESS precision than drivers. */
const val PRESENCE_POSITION_GRID_M: Double = 100.0

fun quantizePosition(meters: Double, gridM: Double): Int =
    Math.round(meters / gridM).toInt()

data class BeaconPayload(
    val rotatingId: ByteArray,
    val latQuantized: Int,
    val lonQuantized: Int,
    val rideTypeBitmap: Int,
    val available: Boolean,
    val keyEpoch: Int,
    val profile: ByteArray?,
    val shortMac: ByteArray,
)

sealed class BeaconDecode {
    data class Ok(val payload: BeaconPayload, val macVerified: Boolean) : BeaconDecode()
    data class MacInvalid(val payload: BeaconPayload) : BeaconDecode()
    data class Malformed(val reason: String) : BeaconDecode()
}

object BeaconCodec {

    const val FIXED_LEN = 8 + 4 + 4 + 1 + 1 + 4 + 2 // 24 bytes before profile + mac
    const val SHORT_MAC_LEN = 8

    fun encode(
        rotatingId: ByteArray,
        latMeters: Double,
        lonMeters: Double,
        rideTypeBitmap: Int,
        available: Boolean,
        keyEpoch: Int,
        macKey: ByteArray,
        profile: ByteArray? = null,
    ): ByteArray {
        require(rotatingId.size == 8) { "rotatingId must be 8 bytes" }
        require(profile == null || profile.size <= 0xFFFF) { "profile too large" }
        val body = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = body.write(v and 0xFF)
        fun u16(v: Int) { body.write((v ushr 8) and 0xFF); body.write(v and 0xFF) }
        fun i32(v: Int) {
            body.write((v ushr 24) and 0xFF); body.write((v ushr 16) and 0xFF)
            body.write((v ushr 8) and 0xFF); body.write(v and 0xFF)
        }
        body.write(rotatingId)
        i32(quantizePosition(latMeters, BEACON_POSITION_GRID_M))
        i32(quantizePosition(lonMeters, BEACON_POSITION_GRID_M))
        u8(rideTypeBitmap and 0xFF)
        u8(if (available) 1 else 0)
        i32(keyEpoch)
        if (profile == null) u16(0) else { u16(profile.size); body.write(profile) }
        val bytes = body.toByteArray()
        val mac = MeshCrypto.hmacSha256(macKey, bytes).copyOf(SHORT_MAC_LEN)
        return bytes + mac
    }

    /** Never throws: malformed input -> Malformed, hostile MAC -> MacInvalid (GATE 1 charter). */
    fun decode(data: ByteArray, macKey: ByteArray?): BeaconDecode {
        try {
            if (data.size < FIXED_LEN + SHORT_MAC_LEN) return BeaconDecode.Malformed("truncated ${data.size}")
            fun i32(at: Int): Int =
                ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
                    ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
            val rotatingId = data.copyOfRange(0, 8)
            val latQ = i32(8)
            val lonQ = i32(12)
            val rideTypes = data[16].toInt() and 0xFF
            val available = (data[17].toInt() and 0xFF) == 1
            val keyEpoch = i32(18)
            val profileLen = ((data[22].toInt() and 0xFF) shl 8) or (data[23].toInt() and 0xFF)
            val profileEnd = 24 + profileLen
            val macStart = data.size - SHORT_MAC_LEN
            if (profileEnd > macStart) return BeaconDecode.Malformed("profileLen $profileLen overruns")
            val profile = if (profileLen == 0) null else data.copyOfRange(24, profileEnd)
            val mac = data.copyOfRange(macStart, data.size)
            val payload = BeaconPayload(rotatingId, latQ, lonQ, rideTypes, available, keyEpoch, profile, mac)
            if (macKey == null) return BeaconDecode.Ok(payload, macVerified = false)
            val expected = MeshCrypto.hmacSha256(macKey, data.copyOfRange(0, macStart)).copyOf(SHORT_MAC_LEN)
            return if (expected.contentEquals(mac)) BeaconDecode.Ok(payload, macVerified = true)
            else BeaconDecode.MacInvalid(payload)
        } catch (e: Exception) {
            return BeaconDecode.Malformed("unexpected: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * Backend-issued driver certificate: binds the driver id to a profile-signing
 * key; the backend's pinned Ed25519 key is the trust anchor (spec). The driver
 * signs each profile snippet with the certified key; receivers verify both.
 */
data class DriverCertificate(
    val driverId: ByteArray,
    val profileSigningKeyRaw: ByteArray, // 32B Ed25519 raw public
    val issuedAtSec: Int,
    val expiresSec: Int,
    val signature: ByteArray, // by the backend over the canonical region
) {
    fun canonicalBytes(): ByteArray = buildString {
        append("driver-cert-v1|")
        append(driverId.joinToString("") { "%02x".format(it) })
        append("|key=")
        append(profileSigningKeyRaw.joinToString("") { "%02x".format(it) })
        append("|issued=$issuedAtSec|expires=$expiresSec")
    }.toByteArray(Charsets.US_ASCII)
}

data class ProfileSnippet(
    val driverId: ByteArray,
    val displayName: String,
    val ratingX10: Int, // rating 0..50 (4.7 -> 47); never a raw float on the wire
    val vehicleSummary: String,
    val photoHash: ByteArray, // 8B truncated sha256
    val issuedAtSec: Int,
    val signature: ByteArray, // by the certificate's profile-signing key
) {
    fun canonicalBytes(): ByteArray = buildString {
        append("driver-profile-v1|")
        append(driverId.joinToString("") { "%02x".format(it) })
        append("|name=$displayName|rating=$ratingX10|vehicle=$vehicleSummary|photo=")
        append(photoHash.joinToString("") { "%02x".format(it) })
        append("|issued=$issuedAtSec")
    }.toByteArray(Charsets.UTF_8)
}

sealed class ProfileVerdict {
    data object Ok : ProfileVerdict()
    data class Rejected(val reason: String) : ProfileVerdict()
}

object DriverCredentials {

    fun issueCertificate(
        driverId: ByteArray,
        profileSigningKey: java.security.KeyPair,
        issuedAtSec: Int,
        expiresSec: Int,
        backendKey: java.security.KeyPair,
    ): DriverCertificate {
        val cert = DriverCertificate(
            driverId, MeshCrypto.rawEd25519(profileSigningKey.public),
            issuedAtSec, expiresSec, ByteArray(64),
        )
        return cert.copy(signature = MeshCrypto.sign(backendKey.private, cert.canonicalBytes()))
    }

    fun signSnippet(
        snippet: ProfileSnippet,
        profileSigningKey: java.security.KeyPair,
    ): ProfileSnippet = snippet.copy(signature = MeshCrypto.sign(profileSigningKey.private, snippet.canonicalBytes()))

    /** Two-step trust chain: backend signs the certificate, the certificate key signs the snippet. */
    fun verify(
        cert: DriverCertificate,
        snippet: ProfileSnippet,
        backendPublicKey: PublicKey,
        nowSec: Int,
    ): ProfileVerdict {
        if (!MeshCrypto.verify(backendPublicKey, cert.canonicalBytes(), cert.signature)) {
            return ProfileVerdict.Rejected("bad backend signature")
        }
        if (nowSec > cert.expiresSec) return ProfileVerdict.Rejected("certificate expired")
        if (nowSec < cert.issuedAtSec) return ProfileVerdict.Rejected("certificate not yet valid")
        if (!cert.driverId.contentEquals(snippet.driverId)) return ProfileVerdict.Rejected("driver id mismatch")
        val certKey = MeshCrypto.ed25519PublicKeyFromRaw(cert.profileSigningKeyRaw)
        if (!MeshCrypto.verify(certKey, snippet.canonicalBytes(), snippet.signature)) {
            return ProfileVerdict.Rejected("bad profile signature")
        }
        return ProfileVerdict.Ok
    }

    /** beaconMacKey = HKDF(profileSigningKeyRaw, salt=driverId, info="beacon-mac"). */
    fun beaconMacKey(profileSigningKey: java.security.KeyPair, driverId: ByteArray): ByteArray =
        beaconMacKey(MeshCrypto.rawEd25519(profileSigningKey.public), driverId)

    /** Raw-key variant: receivers derive the MAC key from the certificate's key material. */
    fun beaconMacKey(profileSigningKeyRaw: ByteArray, driverId: ByteArray): ByteArray =
        MeshCrypto.hkdfSha256(
            profileSigningKeyRaw,
            salt = driverId,
            info = "beacon-mac".toByteArray(),
            length = 32,
        )
}

/**
 * Rider-side cache of heard driver beacons ("nearby driver markers from beacons",
 * Part B offline UX) and of profile snippets "relayed once and cached with
 * expiry" (spec). Entries expire; one driver's profile is forwarded at most once
 * per cache generation so profile GATT reads stay short.
 */
class BeaconLedger(private val clock: () -> Long) {

    data class Entry(
        val payload: BeaconPayload,
        val heardAtMs: Long,
        val fromNeighbor: String,
    )

    data class ProfileEntry(val snippet: ProfileSnippet, val cachedAtMs: Long, val expiresAtMs: Long)

    private val beacons = LinkedHashMap<String, Entry>() // rotatingId hex -> entry
    private val profiles = HashMap<String, ProfileEntry>() // driverId hex -> entry
    private val certs = HashMap<String, DriverCertificate>() // driverId hex -> verified cert
    private val driverIdByRotating = HashMap<String, String>() // rotatingId hex -> driverId hex
    private val profileForwarded = HashSet<String>() // driverId hex, per generation

    var beaconTtlMs: Long = 30_000
    var profileTtlMs: Long = 300_000

    fun observe(payload: BeaconPayload, nowMs: Long = clock(), fromNeighbor: String) {
        beacons[payload.rotatingId.joinToString("") { "%02x".format(it) }] =
            Entry(payload, nowMs, fromNeighbor)
    }

    fun cacheProfile(snippet: ProfileSnippet, nowMs: Long = clock()) {
        val key = snippet.driverId.joinToString("") { "%02x".format(it) }
        profiles[key] = ProfileEntry(snippet, nowMs, nowMs + profileTtlMs)
        profileForwarded.add(key)
    }

    /** Live entries only (expired silently dropped). */
    fun nearby(nowMs: Long = clock()): List<Entry> {
        val out = mutableListOf<Entry>()
        val it = beacons.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (nowMs - e.heardAtMs <= beaconTtlMs) out.add(e) else it.remove()
        }
        return out
    }

    fun profileFor(driverId: ByteArray, nowMs: Long = clock()): ProfileSnippet? {
        val key = driverId.joinToString("") { "%02x".format(it) }
        val e = profiles[key] ?: return null
        if (nowMs > e.expiresAtMs) {
            profiles.remove(key)
            profileForwarded.remove(key)
            return null
        }
        return e.snippet
    }

    /** True at most once per driver per cache generation (profile "relayed once" rule). */
    fun shouldRelayProfile(driverId: ByteArray, nowMs: Long = clock()): Boolean {
        val key = driverId.joinToString("") { "%02x".format(it) }
        if (key in profileForwarded) return false
        profileForwarded.add(key)
        return true
    }

    fun profileCount(): Int = profiles.size

    /** Pairs a heard rotating beacon id with the verified certificate (rider-side mapping). */
    fun mapRotating(rotatingId: ByteArray, cert: DriverCertificate) {
        driverIdByRotating[rotatingId.joinToString("") { "%02x".format(it) }] =
            cert.driverId.joinToString("") { "%02x".format(it) }
        certs[cert.driverId.joinToString("") { "%02x".format(it) }] = cert
    }

    /** (profileSigningKeyRaw, driverId) for a heard rotating beacon, or null when uncertified/expired. */
    fun snapshotFor(rotatingId: ByteArray): Pair<ByteArray, ByteArray>? {
        val driverIdHex = driverIdByRotating[rotatingId.joinToString("") { "%02x".format(it) }] ?: return null
        val cert = certs[driverIdHex] ?: return null
        return cert.profileSigningKeyRaw to cert.driverId
    }
}

/**
 * A driver's beacon material: the certified profile-signing key, the driver id
 * and the signed certificate + snippet that ride the first beacon.
 */
data class DriverBeaconCredentials(
    val profileSigningKey: java.security.KeyPair,
    val driverId: ByteArray,
    val certificate: DriverCertificate,
    val snippet: ProfileSnippet,
)

/**
 * Adaptive beacon pacing (spec: "every beaconIntervalSec (default 5 s,
 * admin-configurable, adaptive when many nodes heard)"). Dense worlds beacon
 * less often: the interval grows with the heard-neighbor count above
 * [denseThreshold], capped at [maxIntervalSec], so a packed grid cannot flood
 * the channel with beacons.
 */
class BeaconScheduler(
    private val clock: () -> Long,
    var baseIntervalSec: Int = 5,
    var maxIntervalSec: Int = 15,
    var denseThreshold: Int = 6,
) {
    private var nextDueMs: Long = 0

    /** Interval for the CURRENT neighbor count. */
    fun intervalSec(heardNeighbors: Int): Int {
        if (heardNeighbors <= denseThreshold) return baseIntervalSec
        val factor = 1.0 + (heardNeighbors - denseThreshold) * 0.25
        return (baseIntervalSec * factor).toInt().coerceAtMost(maxIntervalSec)
    }

    /** Returns true exactly when a beacon is due (call once per tick). */
    fun shouldEmit(heardNeighbors: Int, nowMs: Long = clock()): Boolean {
        if (nowMs < nextDueMs) return false
        nextDueMs = nowMs + intervalSec(heardNeighbors) * 1000L
        return true
    }

    /** Test/verification hook: does not consume the due tick. */
    fun peekNextDueMs(): Long = nextDueMs
}

/**
 * Wire format for the beacon's optional profile field: the backend-issued
 * certificate + the signed profile snippet travel together so any receiver can
 * verify the whole chain against the pinned backend key (spec: "verify against
 * backend-issued driver certificates").
 *
 *   certLen u16 | cert (fixed 112B) | snippet
 *   cert:  driverId 8B | profileSigningKeyRaw 32B | issued u32 | expires u32 | sig 64B
 *   snippet: driverId 8B | nameLen u8 | name | ratingX10 u8 | vehLen u8 | vehicle |
 *            photoHash 8B | issued u32 | sig 64B
 */
object ProfileWire {

    const val CERT_LEN = 8 + 32 + 4 + 4 + 64

    fun encodeBundle(cert: DriverCertificate, snippet: ProfileSnippet): ByteArray {
        require(cert.driverId.size == 8 && cert.profileSigningKeyRaw.size == 32)
        require(snippet.driverId.size == 8 && snippet.photoHash.size == 8)
        require(snippet.displayName.toByteArray(Charsets.UTF_8).size <= 0xFF)
        require(snippet.vehicleSummary.toByteArray(Charsets.UTF_8).size <= 0xFF)
        val name = snippet.displayName.toByteArray(Charsets.UTF_8)
        val vehicle = snippet.vehicleSummary.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        fun i32(v: Int) {
            out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
        }
        u16(CERT_LEN)
        out.write(cert.driverId)
        out.write(cert.profileSigningKeyRaw)
        i32(cert.issuedAtSec)
        i32(cert.expiresSec)
        out.write(cert.signature) // 64B
        out.write(snippet.driverId)
        u8(name.size); out.write(name)
        u8(snippet.ratingX10 and 0xFF)
        u8(vehicle.size); out.write(vehicle)
        out.write(snippet.photoHash)
        i32(snippet.issuedAtSec)
        out.write(snippet.signature) // 64B
        return out.toByteArray()
    }

    /** Never throws. Returns null on any structural deviation. */
    fun decodeBundle(bytes: ByteArray): Pair<DriverCertificate, ProfileSnippet>? {
        try {
            if (bytes.size < 2 + CERT_LEN) return null
            val certLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            if (certLen != CERT_LEN || bytes.size < 2 + certLen) return null
            var p = 2
            fun take(n: Int): ByteArray { val b = bytes.copyOfRange(p, p + n); p += n; return b }
            fun i32(): Int {
                val v = ((bytes[p].toInt() and 0xFF) shl 24) or ((bytes[p + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)
                p += 4
                return v
            }
            val cert = DriverCertificate(
                driverId = take(8), profileSigningKeyRaw = take(32),
                issuedAtSec = i32(), expiresSec = i32(), signature = take(64),
            )
            if (p + 8 > bytes.size) return null
            val driverId = take(8)
            val nameLen = bytes[p++].toInt() and 0xFF
            if (p + nameLen > bytes.size) return null
            val name = String(take(nameLen), Charsets.UTF_8)
            val rating = bytes[p++].toInt() and 0xFF
            val vehLen = bytes[p++].toInt() and 0xFF
            if (p + vehLen > bytes.size) return null
            val vehicle = String(take(vehLen), Charsets.UTF_8)
            if (p + 8 + 4 + 64 > bytes.size) return null
            val photoHash = take(8)
            val issued = i32()
            val sig = take(64)
            if (p != bytes.size) return null
            return cert to ProfileSnippet(driverId, name, rating, vehicle, photoHash, issued, sig)
        } catch (e: Exception) {
            return null
        }
    }
}

/**
 * PRESENCE from offline riders: coarse position (~100 m grid), rotating ID -
 * sealed to the BACKEND key only. Gateways relay the sealed bytes opaquely and
 * cannot open them (anonymous sealed box, [MeshCrypto.seal]).
 *
 * Plaintext layout: rotatingId 8B | latQ i32 | lonQ i32 | createdAtSec u32.
 */
object PresenceCodec {
    const val PLAINTEXT_LEN = 8 + 4 + 4 + 4

    fun plaintext(rotatingId: ByteArray, latMeters: Double, lonMeters: Double, nowSec: Int): ByteArray {
        require(rotatingId.size == 8)
        val out = java.io.ByteArrayOutputStream()
        fun i32(v: Int) {
            out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
        }
        out.write(rotatingId)
        i32(quantizePosition(latMeters, PRESENCE_POSITION_GRID_M))
        i32(quantizePosition(lonMeters, PRESENCE_POSITION_GRID_M))
        i32(nowSec)
        return out.toByteArray()
    }

    fun seal(rotatingId: ByteArray, latMeters: Double, lonMeters: Double, nowSec: Int, backendX25519: PublicKey): ByteArray =
        MeshCrypto.seal(backendX25519, plaintext(rotatingId, latMeters, lonMeters, nowSec))

    data class Presence(val rotatingId: ByteArray, val latQuantized: Int, val lonQuantized: Int, val createdAtSec: Int)

    /** Backend-side open. Returns null when the sealed bytes were tampered with. */
    fun open(sealedBytes: ByteArray, backendX25519Private: PrivateKey): Presence? {
        // The sealed box is anonymous (ephemeral sender key embedded); the senderPublic
        // parameter of MeshCrypto.open is unused by construction - pass a throwaway key.
        val pt = MeshCrypto.open(backendX25519Private, MeshCrypto.newX25519KeyPair().public, sealedBytes) ?: return null
        if (pt.size != PLAINTEXT_LEN) return null
        fun i32(at: Int): Int =
            ((pt[at].toInt() and 0xFF) shl 24) or ((pt[at + 1].toInt() and 0xFF) shl 16) or
                ((pt[at + 2].toInt() and 0xFF) shl 8) or (pt[at + 3].toInt() and 0xFF)
        return Presence(pt.copyOfRange(0, 8), i32(8), i32(12), i32(16))
    }
}
