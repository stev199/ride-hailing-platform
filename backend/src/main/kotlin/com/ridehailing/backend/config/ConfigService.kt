package com.ridehailing.backend.config

import com.ridehailing.backend.auth.LoginMethodsConfig
import com.ridehailing.backend.auth.OtpChannel
import com.ridehailing.backend.events.EventChain
import com.ridehailing.backend.fares.FareConfig
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
    /** P4-1: fares section of the device-visible bundle (D75 full-replace admin edits). */
    @Volatile private var currentFares: FareConfig = defaultFares()
    /** P7-1: wallet section of the device-visible bundle (D75 full-replace admin edits). */
    @Volatile private var currentWallet: WalletConfig = WalletConfig()
    /** P7-6: promo codes section of the device-visible bundle (D75 full-replace admin edits). */
    @Volatile private var currentPromos: PromoConfig = PromoConfig()
    @Volatile private var currentVersion: Long = 0L
    @Volatile private var currentSigned: SignedConfigBundle.SignedConfig? = null
    private val history = mutableListOf<VersionEntry>()

    /** Live snapshot for auth consumers (pulled per call through the provider lambda). */
    val loginMethods: LoginMethodsConfig
        get() = currentMethods

    /** P4-1: live fares for the FareService provider lambda. */
    val fares: FareConfig
        get() = currentFares

    fun publicKeyHex(): String = bundle.publicKeyBytes.toHex()

    /** P4-2: the Ed25519 bundle for ride-quote signing (same key the devices pin). */
    val quoteSigningBundle: SignedConfigBundle
        get() = bundle

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

/** Live wallet config snapshot for service consumers (provider-lambda pattern). */
    val walletConfig: WalletConfig get() = synchronized(this) { currentWallet }

    /** P7-6: live promo config snapshot (provider-lambda pattern). */
    val promos: PromoConfig get() = synchronized(this) { currentPromos }

    /**
     * P7-6: replace the promo codes section (D75 full-replace). Invalid configs never
     * touch live state and never burn a version.
     */
    fun updatePromos(actorId: String, promos: PromoConfig): UpdateResult {
        val errs = promos.validationErrors()
        if (errs.isNotEmpty()) return UpdateResult.Invalid("promos", errs.joinToString("; "))
        synchronized(this) {
            currentPromos = promos
            val entry = publishLocked(actorId, "promos_updated")
            eventRecorder?.append(
                "config.promos.updated",
                mapOf(
                    "actorId" to actorId,
                    "version" to entry.version.toString(),
                    "codes" to promos.codes.size.toString(),
                ),
            )
            return UpdateResult.Updated(entry)
        }
    }

    /**
     * P7-1: replace the wallet section (D75 full-replace). Invalid configs never
     * touch live state and never burn a version (fares pattern).
     */
    fun updateWallet(actorId: String, wallet: WalletConfig): UpdateResult {
        val errs = wallet.validationErrors()
        if (errs.isNotEmpty()) return UpdateResult.Invalid("wallet", errs.joinToString("; "))
        synchronized(this) {
            currentWallet = wallet
            val entry = publishLocked(actorId, "wallet_updated")
            eventRecorder?.append(
                "config.wallet.updated",
                mapOf(
                    "actorId" to actorId,
                    "version" to entry.version.toString(),
                    "transferFeeBps" to wallet.transferFeeBps.toString(),
                ),
            )
            return UpdateResult.Updated(entry)
        }
    }

    /** Canonical signed payload: login methods + fares + wallet + promos (device-visible config). */
    private fun configCanonicalJson(m: LoginMethodsConfig, f: FareConfig, w: WalletConfig, p: PromoConfig): String =
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
                "fares" to faresToCanonicalMap(f),
                "wallet" to mapOf(
                    "transferFeeBps" to w.transferFeeBps,
                    "minTransferMinor" to w.minTransferMinor,
                    "dailyTransferLimitMinor" to w.dailyTransferLimitMinor,
                ),
                "promos" to mapOf(
                    "codes" to p.codes.map { c ->
                        mapOf(
                            "code" to c.code,
                            "kind" to c.kind,
                            "value" to c.value,
                            "currency" to c.currency,
                            "validFromEpochSec" to c.validFromEpochSec,
                            "validUntilEpochSec" to c.validUntilEpochSec,
                            "perUserLimit" to c.perUserLimit,
                            "active" to c.active,
                        )
                    },
                ),
            ),
        )

    /**
     * P4-1: replace the fares section (D75 full-replace semantics: the admin UI
     * sends the edited document; the server validates it completely). Invalid
     * configs never touch the live state and never burn a version.
     */
    fun updateFares(actorId: String, fares: FareConfig): UpdateResult {
        val errs = fares.validationErrors()
        if (errs.isNotEmpty()) return UpdateResult.Invalid("fares", errs.joinToString("; "))
        synchronized(this) {
            currentFares = fares
            val entry = publishLocked(actorId, "fares_updated")
            eventRecorder?.append(
                "config.fares.updated",
                mapOf(
                    "actorId" to actorId,
                    "version" to entry.version.toString(),
                    "cities" to fares.cities.keys.sorted().joinToString(","),
                ),
            )
            return UpdateResult.Updated(entry)
        }
    }

    private fun publishLocked(actorId: String, change: String): VersionEntry {
        val version = currentVersion + 1
        val issuedAt = clock().epochSecond
        val canonical = configCanonicalJson(currentMethods, currentFares, currentWallet, currentPromos)
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

    /** Fares section of the canonical JSON - IDENTICAL shape to the admin PATCH body (zero drift by construction). */
    private fun faresToCanonicalMap(f: FareConfig): Map<String, Any?> = mapOf(
        "straightLine" to mapOf(
            "detourFactor" to f.straightLine.detourFactor,
            "assumedAvgSpeedKmh" to f.straightLine.assumedAvgSpeedKmh,
        ),
        "quote" to mapOf(
            "validitySeconds" to f.quote.validitySeconds,
            "mode" to f.quote.mode,
            "meteredCapMultiplier" to f.quote.meteredCapMultiplier,
        ),
        "cities" to f.cities.entries.associate { (city, c) ->
            city to mapOf(
                "currency" to c.currency,
                "rideTypes" to c.rideTypes.entries.associate { (name, rt) ->
                    name to mapOf(
                        "base" to rt.base,
                        "perKm" to rt.perKm,
                        "perMinute" to rt.perMinute,
                        "minimumFare" to rt.minimumFare,
                        "bookingFee" to rt.bookingFee,
                        "cancelFee" to rt.cancelFee,
                        "waitFeePerMinute" to rt.waitFeePerMinute,
                        "night" to mapOf(
                            "multiplier" to rt.night.multiplier,
                            "startHourUtc" to rt.night.startHourUtc,
                            "endHourUtc" to rt.night.endHourUtc,
                        ),
                        "peak" to mapOf(
                            "multiplier" to rt.peak.multiplier,
                            "startHourUtc" to rt.peak.startHourUtc,
                            "endHourUtc" to rt.peak.endHourUtc,
                        ),
                    )
                },
            )
        },
    )
}

/**
 * Bootstrap fares (P4-1): the geo stack ships a Monaco extract, so the seeded
 * city is "monaco" with one standard ride type - a fresh deployment estimates
 * without admin action; operators replace the section via PATCH (D75).
 */
fun defaultFares(): FareConfig = FareConfig(
    cities = mapOf(
        "monaco" to com.ridehailing.backend.fares.CityFares(
            currency = "EUR",
            rideTypes = mapOf(
                "standard" to com.ridehailing.backend.fares.RideTypeFares(
                    base = 2.5, perKm = 1.8, perMinute = 0.45, minimumFare = 8.0,
                    bookingFee = 1.5, cancelFee = 3.0, waitFeePerMinute = 0.5,
                ),
            ),
        ),
    ),
)

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
