package com.ridehailing.mesh.protocol

import com.ridehailing.mesh.crypto.MeshCrypto
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Envelope validation (relays + gateway). GATE 1: flipping any bit of the immutable
 * header (or payload) must cause rejection; expired TTL and hop-limit violations reject;
 * unknown versions are ignored gracefully by the codec.
 */
object Validator {

    sealed class Verdict {
        data object Valid : Verdict()
        data class Invalid(val reason: String) : Verdict()
    }

    /**
     * @param ourConfiguredMaxHops our signed config value; relays enforce the MIN of
     *        origin-stamped maxHops and their own config (spec).
     * @param nowSec unix seconds.
     * @param originPublicKey public key of the origin (from profile/cert cache); when
     *        null the signature check is skipped (caller MUST NOT accept critical
     *        messages without it - enforced at the gateway).
     * @param linkKey neighbor link key for the hop MAC; null defers MAC check.
     */
    fun validate(
        envelope: Envelope,
        ourConfiguredMaxHops: Int,
        nowSec: Int,
        originPublicKey: PublicKey?,
        linkKey: ByteArray? = null,
    ): Verdict {
        if (envelope.version > PROTOCOL_VERSION) return Verdict.Invalid("unknown version")
        if (envelope.createdAtSec + envelope.ttlSec < nowSec) return Verdict.Invalid("expired ttl")
        val effectiveMax = minOf(envelope.maxHops, ourConfiguredMaxHops)
        if (envelope.hopSection.hopCount > effectiveMax) return Verdict.Invalid("hopCount ${envelope.hopSection.hopCount} > max $effectiveMax")
        if (originPublicKey != null) {
            val ok = MeshCrypto.verify(originPublicKey, Codec.immutableRegion(envelope), envelope.originSignature)
            if (!ok) return Verdict.Invalid("bad origin signature")
        }
        if (linkKey != null) {
            val expected = MeshCrypto.hopMac(linkKey, Codec.hopSectionRegion(envelope))
            if (!expected.contentEquals(envelope.hopSection.hopMac)) return Verdict.Invalid("bad hop MAC")
        }
        return Verdict.Valid
    }

    /** Relay forwarding: increments hopCount, appends breadcrumb, re-MACs with the next link key. */
    fun relay(
        envelope: Envelope,
        ourConfiguredMaxHops: Int,
        nowSec: Int,
        hopIdHash: ByteArray,
        linkQuality: Int,
        loadClass: Int,
        nextLinkKey: ByteArray?,
        originPublicKey: PublicKey?,
    ): RelayResult {
        when (val check = validate(envelope, ourConfiguredMaxHops, nowSec, originPublicKey)) {
            is Verdict.Invalid -> return RelayResult.Dropped(check.reason)
            else -> {}
        }
        // hopCount counts COMPLETED links (origin sends 0; each relay adds one).
        // The budget counts LINK HOPS, so at most maxHops-1 relays may forward: the
        // final relay's transmission is the link INTO the destination. Refusing here
        // means an over-budget envelope never reaches the destination (GATE 3
        // scenario 2: "no relay beyond limit"). GATE 3 caught the previous off-by-one
        // (which allowed maxHops+1 links).
        if (envelope.hopSection.hopCount >= minOf(envelope.maxHops, ourConfiguredMaxHops) - 1) {
            return RelayResult.Dropped("hop budget exhausted")
        }
        val newCrumbs = envelope.hopSection.breadcrumbs + Breadcrumb(hopIdHash, linkQuality, loadClass)
        val forwarded = envelope.copy(
            hopSection = HopSection(
                hopCount = envelope.hopSection.hopCount + 1,
                breadcrumbs = newCrumbs,
                hopMac = nextLinkKey?.let { ByteArray(16) } ?: envelope.hopSection.hopMac,
            ),
        )
        if (nextLinkKey != null) {
            val mac = MeshCrypto.hopMac(nextLinkKey, Codec.hopSectionRegion(forwarded))
            return RelayResult.Forwarded(forwarded.copy(hopSection = forwarded.hopSection.copy(hopMac = mac)))
        }
        return RelayResult.Forwarded(forwarded)
    }

    /** Origin-side builder: builds a signed, sealed envelope. */
    fun buildEnvelope(
        type: MessageType,
        flags: Int,
        destination: Destination,
        payload: ByteArray,
        maxHops: Int,
        ttlSec: Int,
        identity: java.security.KeyPair,
        originId: ByteArray,
        originEpoch: Int,
        createdAtSec: Int,
    ): Envelope {
        val unsigned = Envelope(
            version = PROTOCOL_VERSION, type = type, flags = flags,
            msgId = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
            originId = originId, originEpoch = originEpoch, destination = destination,
            createdAtSec = createdAtSec, ttlSec = ttlSec, maxHops = maxHops,
            payload = payload, originSignature = ByteArray(64),
            hopSection = HopSection(0, emptyList(), ByteArray(16)),
        )
        val sig = MeshCrypto.sign(identity.private as PrivateKey, Codec.immutableRegion(unsigned))
        return unsigned.copy(originSignature = sig)
    }

    sealed class RelayResult {
        data class Forwarded(val envelope: Envelope) : RelayResult()
        data class Dropped(val reason: String) : RelayResult()
    }
}
