package com.ridehailing.backend.config

import com.ridehailing.backend.auth.LoginMethodsConfig
import com.ridehailing.backend.auth.OtpChannel
import com.ridehailing.backend.events.EventChain
import kotlinx.serialization.Serializable

/**
 * P1-5a config service: owns the LIVE LoginMethodsConfig and the versioned, signed
 * config bundle history (B-01/A-10).
 *
 * - Devices read `GET /v1/config` (unauthenticated, pre-login): the current bundle
 *   {version, configCanonicalJson, issuedAtEpochSec, signature, publicKey}. The
 *   signature is Ed25519 over "version|canonical(config)|issuedAtSec" (SignedConfigBundle);
 *   devices verify against the pinned public key and reject version <= last accepted
 *   (anti-rollback).
 * - Admins read `GET /v1/admin/config/login-methods` (CONFIG_READ) and patch
 *   `PATCH /v1/admin/config/login-methods` (CONFIG_WRITE). Every accepted patch bumps
 *   the version, re-signs the bundle and appends a `config.login_methods.updated`
 *   audit event (D63: every admin mutation is audited).
 * - AuthService/OtpService receive a `() -> LoginMethodsConfig` PROVIDER, so an admin
 *   patch takes effect on the very next call without rebuilding the graph.
 *
 * Storage is in-memory for P1-5a (same port pattern as the P1-3 stores; the PG-backed
 * config store lands with the next storage pass). Version history keeps every entry -
 * bundles are tiny and the device anti-rollback check only needs the current version.
 */
class ConfigService(
    private val bundle: SignedConfigBundle,
    private val eventRecorder: EventRecorder? = null,
    private val clock: () -> java.time.Instant = java.time.Instant::now,
) {
    fun interface EventRecorder {
        fun append(type: String, payload: Map<String, String?>)
    }

    data class VersionEntry(
        val version: Long,
        val issuedAtEpochSec: Long,
        val configCanonicalJson: String,
        val signatureHex: String,
        val changedBy: String,
        val change: String,
    )

    sealed class UpdateResult {
        data class Updated(val entry: VersionEntry) : UpdateResult()
        data class Invalid(val field: String, val code: String) : UpdateResult()
    }

    @Volatile private var currentMethods: LoginMethodsConfig = LoginMethodsConfig()
    @Volatile private var currentVersion: Long = 0L
    @Volatile private var currentSigned: SignedConfigBundle.SignedConfig? = null
    private val history = mutableListOf<VersionEntry>()

    /** Live snapshot for auth consumers (pulled per call through the provider lambda). */
    val loginMethods: LoginMethodsConfig
        get() = currentMethods

    fun publicKeyHex(): String = bundle.publicKeyBytes.toHex()

    /**
     * The current signed bundle. Version 1 is published lazily on first read
     * (bootstrap entry, actor "system") so a fresh deployment serves a valid bundle
     * without any admin action.
     */
    fun currentBundle(): SignedConfigBundle.SignedConfig {
        synchronized(this) {
            if (currentSigned == null) publishLocked("system", "bootstrap")
            return currentSigned!!
        }
    }

    fun historySnapshot(): List<VersionEntry> = synchronized(this) { history.toList() }

    /**
     * Patch the live login methods. `devOnly` is intentionally NOT patchable - it is a
     * deployment property of the adapter (D37), not an admin preference. Unknown channel
     * names and negative priorities are rejected; priorities are admin-set ordering
     * (lower first) and must stay distinct per enabled channel to keep the order total.
     */
    fun updateLoginMethods(actorId: String, patch: LoginMethodsPatch): UpdateResult {
        // A patch that touches NOTHING must not burn a version (anti-noise): 400 empty_patch.
        if (patch.passwordLoginEnabled == null && patch.otpAutoRegister == null &&
            (patch.otpChannels == null || patch.otpChannels.isEmpty())
        ) {
            return UpdateResult.Invalid("patch", "empty_patch")
        }
        // P1-CONTRACT round 5 (ST201): an empty channel map inside a NON-empty patch is
        // equally content-free - the openapi schema forbids it (minProperties 1), so the
        // server must reject it with the same anti-noise code instead of publishing a
        // version that changes nothing.
        if (patch.otpChannels?.isEmpty() == true) {
            return UpdateResult.Invalid("otpChannels", "empty_patch")
        }
        patch.otpChannels?.forEach { (name, ch) ->
            if (OtpChannel.entries.none { it.name == name }) {
                return UpdateResult.Invalid("otpChannels.$name", "unknown_channel")
            }
            if (ch.priority != null && ch.priority < 0) {
                return UpdateResult.Invalid("otpChannels.$name.priority", "invalid_priority")
            }
        }
        synchronized(this) {
            var m = currentMethods
            patch.passwordLoginEnabled?.let { m = m.copy(passwordLoginEnabled = it) }
            patch.otpAutoRegister?.let { m = m.copy(otpAutoRegister = it) }
            patch.otpChannels?.forEach { (name, ch) ->
                val channel = OtpChannel.valueOf(name)
                val old = m.otpChannels[channel] ?: LoginMethodsConfig.ChannelConfig(enabled = false, priority = 100)
                m = m.copy(
                    otpChannels = m.otpChannels + (channel to old.copy(
                        enabled = ch.enabled ?: old.enabled,
                        priority = ch.priority ?: old.priority,
                    )),
                )
            }
            // Total order check across ENABLED channels (ties would make prioritizedChannels unstable).
            val enabledPriorities = m.otpChannels.filter { it.value.enabled }.map { it.value.priority }
            if (enabledPriorities.size != enabledPriorities.distinct().size) {
                return UpdateResult.Invalid("otpChannels.priority", "duplicate_priority")
            }
            currentMethods = m
            val entry = publishLocked(actorId, "login_methods_updated")
            eventRecorder?.append(
                "config.login_methods.updated",
                mapOf(
                    "actorId" to actorId,
                    "version" to entry.version.toString(),
                    "patchJson" to EventChain.canonicalJson(patchToAuditMap(patch)),
                ),
            )
            return UpdateResult.Updated(entry)
        }
    }

    /** Canonical signed payload: login methods only for v0 scope (device-visible config). */
    private fun configCanonicalJson(m: LoginMethodsConfig): String =
        EventChain.canonicalJson(
            mapOf(
                "loginMethods" to mapOf(
                    "passwordLoginEnabled" to m.passwordLoginEnabled,
                    "otpAutoRegister" to m.otpAutoRegister,
                    "otpChannels" to m.otpChannels.entries.associate { (ch, cfg) ->
                        ch.name to mapOf(
                            "enabled" to cfg.enabled,
                            "priority" to cfg.priority,
                            "devOnly" to cfg.devOnly,
                        )
                    },
                ),
            ),
        )

    private fun publishLocked(actorId: String, change: String): VersionEntry {
        val version = currentVersion + 1
        val issuedAt = clock().epochSecond
        val canonical = configCanonicalJson(currentMethods)
        val signed = bundle.sign(canonical, version, issuedAt)
            ?: throw IllegalStateException("config bundle has no private key (read-only bundle?)")
        currentVersion = version
        currentSigned = signed
        val entry = VersionEntry(version, issuedAt, canonical, signed.signature.toHex(), actorId, change)
        history.add(entry)
        return entry
    }

    private fun patchToAuditMap(patch: LoginMethodsPatch): Map<String, Any?> = buildMap {
        patch.passwordLoginEnabled?.let { put("passwordLoginEnabled", it) }
        patch.otpAutoRegister?.let { put("otpAutoRegister", it) }
        patch.otpChannels?.let { chs ->
            put(
                "otpChannels",
                chs.entries.associate { (name, ch) ->
                    name to buildMap<String, Any?> {
                        ch.enabled?.let { put("enabled", it) }
                        ch.priority?.let { put("priority", it) }
                    }
                },
            )
        }
    }
}

/** Admin patch body (mirrored in docs/api/openapi.yaml). Absent fields are no-ops. */
@Serializable
data class LoginMethodsPatch(
    val passwordLoginEnabled: Boolean? = null,
    val otpAutoRegister: Boolean? = null,
    val otpChannels: Map<String, ChannelPatch>? = null,
)

@Serializable
data class ChannelPatch(
    val enabled: Boolean? = null,
    // JSON Schema integer semantics: 0.0/3.0/1e3 are schema-valid integers
    // (zero fractional part) - strict kotlinx parse would 400 them (ST202).
    @Serializable(with = ZeroFractionIntSerializer::class) val priority: Int? = null,
)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
