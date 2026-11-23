package com.ridehailing.mesh.config

import com.ridehailing.mesh.crypto.MeshCrypto
import com.ridehailing.mesh.routing.Weights

/**
 * Signed mesh configuration bundle (Part C Stage 2/3: "all from signed admin config",
 * MessageType.CONFIG_SYNC). mesh-core keeps its own minimal bundle codec - it must
 * not depend on the backend module (zero cross-module dependency by design).
 *
 * Anti-rollback: a bundle is accepted only when
 *   1. the Ed25519 signature over the canonical region verifies against the admin key;
 *   2. nowSec is inside [validFromSec, expiresSec];
 *   3. epoch is strictly greater than the last accepted epoch (stale/rolled-back
 *      bundles are rejected - GATE 3 scenario 13).
 */
data class MeshConfig(
    val maxHops: Int = 3,
    val weights: Weights = Weights(),
    val beaconIntervalSec: Int = 5,
    val presenceIntervalSec: Int = 30,
)

data class MeshConfigBundle(
    val epoch: Long,
    val validFromSec: Int,
    val expiresSec: Int,
    val config: MeshConfig,
    val signature: ByteArray,
) {
    fun canonicalBytes(): ByteArray = canonicalRegion(epoch, validFromSec, expiresSec, config).toByteArray(Charsets.UTF_8)

    companion object {
        fun canonicalRegion(epoch: Long, validFromSec: Int, expiresSec: Int, config: MeshConfig): String {
            val w = config.weights
            return "mesh-config-v1" +
                "|epoch=$epoch" +
                "|validFrom=$validFromSec" +
                "|expires=$expiresSec" +
                "|maxHops=${config.maxHops}" +
                "|beacon=${config.beaconIntervalSec}" +
                "|presence=${config.presenceIntervalSec}" +
                "|weights=${w.hop},${w.etx},${w.batt},${w.load},${w.stab},${w.lat},${w.gw}"
        }
    }
}

sealed class ConfigVerdict {
    data class Accepted(val bundle: MeshConfigBundle) : ConfigVerdict()
    data class Rejected(val reason: String) : ConfigVerdict()
}

object MeshConfigManager {

    fun sign(config: MeshConfig, epoch: Long, validFromSec: Int, expiresSec: Int, adminKey: java.security.KeyPair): MeshConfigBundle {
        val unsigned = MeshConfigBundle(epoch, validFromSec, expiresSec, config, ByteArray(64))
        val sig = MeshCrypto.sign(adminKey.private, unsigned.canonicalBytes())
        return unsigned.copy(signature = sig)
    }

    /**
     * Verifies and version-checks [bundle]. [lastAcceptedEpoch] = highest epoch applied
     * so far (or Long.MIN_VALUE). Expired/not-yet-valid bundles are rejected, as are
     * stale (epoch <= lastAccepted) ones - a rollback attempt can never win.
     */
    fun verify(
        bundle: MeshConfigBundle,
        adminPublicKey: java.security.PublicKey,
        nowSec: Int,
        lastAcceptedEpoch: Long,
    ): ConfigVerdict {
        if (!MeshCrypto.verify(adminPublicKey, bundle.canonicalBytes(), bundle.signature)) {
            return ConfigVerdict.Rejected("bad signature")
        }
        if (nowSec < bundle.validFromSec) return ConfigVerdict.Rejected("not yet valid")
        if (nowSec > bundle.expiresSec) return ConfigVerdict.Rejected("expired")
        if (bundle.epoch <= lastAcceptedEpoch) {
            return ConfigVerdict.Rejected("stale epoch ${bundle.epoch} (last accepted $lastAcceptedEpoch)")
        }
        return ConfigVerdict.Accepted(bundle)
    }
}
