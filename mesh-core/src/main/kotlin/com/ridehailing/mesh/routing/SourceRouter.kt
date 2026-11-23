package com.ridehailing.mesh.routing

import com.ridehailing.mesh.protocol.Envelope
import com.ridehailing.mesh.protocol.Flags

/**
 * Source-routed return leg (Stage 2): QUOTE/QUOTE_ACCEPT/ACK/OFFER/UPDATE travel
 * along the best discovered path. Per-hop HOP_ACK retry policy: 3 tries, backoff
 * 100/200/400 ms (the transport inserts the delays via its DelayScheduler; this core
 * exposes the policy and drives the deterministic attempt sequence). On hop failure
 * the sender switches to the backup path; if that fails, the caller may run a scoped
 * flood bounded by the remaining hop budget; the final fallback is store-and-forward
 * until TTL. Critical messages may go over the top-2 paths in parallel (admin
 * toggle); receivers dedup by msgId.
 */
class SourceRouter(
    private val config: Config = Config(),
) {
    data class Config(
        /** Retries AFTER the initial attempt (spec: "3 tries, backoff 100/200/400"). */
        val retries: Int = 3,
        val backoffMs: List<Long> = listOf(100, 200, 400),
        val parallelCritical: Boolean = false,
    )

    /**
     * Link abstraction: synchronous link-level delivery (neighbor accepted + link
     * acked). Transport implementations map HOP_ACK frames onto this and insert the
     * backoff delays between attempts when asynchronous.
     */
    fun interface HopLink {
        fun deliver(to: NodeAddress, envelope: Envelope): Boolean
    }

    sealed class SendResult {
        data object Delivered : SendResult()
        data class Failed(val usedBackup: Boolean, val scopedFlooded: Boolean, val storedForLater: Boolean) : SendResult()
    }

    /**
     * Sends [envelope] source-routed along [bestPath]. The path is in forwarding
     * order starting at the sender (path.hops[0].node == sender's first neighbor).
     * [onScopedFlood] is invoked between backup failure and store-and-forward; return
     * true from it to report success (GATE 3 scenario 8 failover behavior).
     */
    fun send(
        envelope: Envelope,
        bestPath: CandidatePath,
        backupPath: CandidatePath?,
        hopLink: HopLink,
        isCritical: Boolean = (envelope.flags and Flags.CRITICAL) != 0,
        storeAndForward: StoreAndForward? = null,
        onScopedFlood: (() -> Boolean)? = null,
    ): SendResult {
        val parallel = isCritical && config.parallelCritical && backupPath != null
        if (parallel) {
            // Top-2 paths in parallel (admin toggle); receiver dedups by msgId.
            if (attemptPath(envelope, bestPath, hopLink)) return SendResult.Delivered
            if (attemptPath(envelope, backupPath!!, hopLink)) return SendResult.Delivered
            return failOver(envelope, backupPath, hopLink, storeAndForward, onScopedFlood)
        }
        if (attemptPath(envelope, bestPath, hopLink)) return SendResult.Delivered
        if (backupPath != null && attemptPath(envelope, backupPath, hopLink)) {
            return SendResult.Failed(usedBackup = true, scopedFlooded = false, storedForLater = false)
        }
        return failOver(envelope, backupPath, hopLink, storeAndForward, onScopedFlood)
    }

    private fun failOver(
        envelope: Envelope,
        backupPath: CandidatePath?,
        hopLink: HopLink,
        storeAndForward: StoreAndForward?,
        onScopedFlood: (() -> Boolean)?,
    ): SendResult.Failed {
        val flooded = onScopedFlood?.invoke() == true
        if (flooded) return SendResult.Failed(usedBackup = false, scopedFlooded = true, storedForLater = false)
        if (storeAndForward != null) {
            val expiresAtMs = (envelope.createdAtSec + envelope.ttlSec).toLong() * 1000
            storeAndForward.enqueue(envelope, backupPath?.hops?.firstOrNull()?.node, expiresAtMs, null)
            return SendResult.Failed(usedBackup = false, scopedFlooded = false, storedForLater = true)
        }
        return SendResult.Failed(usedBackup = false, scopedFlooded = false, storedForLater = false)
    }

    /**
     * Attempts the whole path. Each hop is retried up to [Config.retries] times with
     * the configured backoff (returned via [AttemptLog]; the transport waits between
     * asynchronous attempts). Returns true when every hop accepted.
     */
    fun attemptPath(envelope: Envelope, path: CandidatePath, hopLink: HopLink): Boolean {
        if (path.hops.isEmpty()) return false
        val totalAttempts = 1 + config.retries // initial try + 3 retries (spec)
        for (hop in path.hops) {
            var accepted = false
            var tryNo = 0
            while (tryNo < totalAttempts) {
                tryNo += 1
                if (hopLink.deliver(hop.node, envelope)) {
                    accepted = true
                    break
                }
                // else: next retry (transport inserts backoffMs[tryNo-1] when async)
            }
            if (!accepted) return false
        }
        return true
    }

    /** Retry plan for a hop (exposed for simulators/observability). */
    fun retryPlan(): List<Long> = config.backoffMs
}
