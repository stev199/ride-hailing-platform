package com.ridehailing.core.data.pricing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ridehailing.core.data.di.ClockMs
import com.ridehailing.core.data.prefs.FareDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The cached device copy of the signed config bundle's fare-relevant envelope. */
data class CachedFareBundle(
    val version: Long,
    val configJson: String,
    val issuedAtEpochSec: Long,
    val fetchedAtEpochSec: Long,
    /** true/false when the platform could verify the Ed25519 signature, null when unsupported. */
    val signatureVerified: Boolean?,
)

/** Last online route measurement (P4-4): the offline estimator's "cached route" input. */
@kotlinx.serialization.Serializable
data class CachedRoute(
    val pickupLat: Double,
    val pickupLon: Double,
    val dropoffLat: Double,
    val dropoffLon: Double,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val cachedAtEpochSec: Long,
)

/**
 * Fare-bundle cache (P4-3, spec §pricing "a signed, versioned fare-config bundle cached
 * while online (expiry and stale-config flag)"):
 *
 * - DataStore persistence survives process death and airplane mode.
 * - ANTI-ROLLBACK (SignedConfigBundle semantics): a bundle whose version is <= the
 *   highest version ever accepted is rejected on save.
 * - EXPIRY/STALE: the cache never auto-deletes (offline-first); [isStale] flags bundles
 *   older than [DEFAULT_TTL_SEC] so the estimator can warn while still estimating.
 * - Signature verification is recorded (true/false/null-unsupported), not enforced -
 *   see [FareBundleVerifier] for the platform-availability contract.
 */
@Singleton
class FareBundleCache @Inject constructor(
    @FareDataStore private val dataStore: DataStore<Preferences>,
    @ClockMs private val clockMs: () -> Long,
) {
    private object Keys {
        val version = longPreferencesKey("fares.version")
        val config = stringPreferencesKey("fares.config_json")
        val issuedAt = longPreferencesKey("fares.issued_at_epoch_sec")
        val fetchedAt = longPreferencesKey("fares.fetched_at_epoch_sec")
        val sigVerified = stringPreferencesKey("fares.signature_verified")
        val lastRoute = stringPreferencesKey("fares.last_route_json")
    }

    private val routeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    val bundleFlow: Flow<CachedFareBundle?> = dataStore.data.map { p ->
        val config = p[Keys.config] ?: return@map null
        CachedFareBundle(
            version = p[Keys.version] ?: 0L,
            configJson = config,
            issuedAtEpochSec = p[Keys.issuedAt] ?: 0L,
            fetchedAtEpochSec = p[Keys.fetchedAt] ?: 0L,
            signatureVerified = when (p[Keys.sigVerified]) {
                "true" -> true
                "false" -> false
                else -> null
            },
        )
    }

    suspend fun bundle(): CachedFareBundle? = bundleFlow.first()

    suspend fun lastAcceptedVersion(): Long = dataStore.data.first()[Keys.version] ?: 0L

    /**
     * Persist an envelope fetched from GET /v1/config. Returns false (and writes
     * NOTHING) when [env.version] is <= the last accepted version (anti-rollback).
     */
    suspend fun save(env: FareBundleEnvelopeDto, signatureVerified: Boolean?): Boolean {
        val current = dataStore.data.first()[Keys.version] ?: 0L
        if (env.version <= current) return false
        dataStore.edit { p ->
            p[Keys.version] = env.version
            p[Keys.config] = env.config
            p[Keys.issuedAt] = env.issuedAtEpochSec
            p[Keys.fetchedAt] = clockMs() / 1000
            p[Keys.sigVerified] = signatureVerified?.toString() ?: ""
        }
        return true
    }

    /** spec §pricing stale flag: fetchedAt older than ttl (defaults 24h). */
    fun isStale(
        bundle: CachedFareBundle,
        nowEpochSec: Long = clockMs() / 1000,
        ttlSec: Long = DEFAULT_TTL_SEC,
    ): Boolean = nowEpochSec - bundle.fetchedAtEpochSec > ttlSec

    /** Persist the last online route measurement (single slot - newest wins). */
    suspend fun saveRoute(route: CachedRoute) {
        dataStore.edit { p ->
            p[Keys.lastRoute] = routeJson.encodeToString(
                CachedRoute.serializer(), route,
            )
        }
    }

    /**
     * The cached route for an EXACT pickup/dropoff pair, when fresh enough. Any other
     * pair (or an expired entry) yields null -> the estimator falls back to the
     * calibrated straight-line (spec §pricing "cached route -> else straight-line").
     */
    suspend fun routeFor(
        pickupLat: Double,
        pickupLon: Double,
        dropoffLat: Double,
        dropoffLon: Double,
        nowEpochSec: Long = clockMs() / 1000,
        maxAgeSec: Long = DEFAULT_ROUTE_TTL_SEC,
    ): CachedRoute? {
        val raw = dataStore.data.first()[Keys.lastRoute] ?: return null
        val route = try {
            routeJson.decodeFromString(CachedRoute.serializer(), raw)
        } catch (_: Exception) {
            return null
        }
        val samePair = route.pickupLat == pickupLat && route.pickupLon == pickupLon &&
            route.dropoffLat == dropoffLat && route.dropoffLon == dropoffLon
        if (!samePair) return null
        if (nowEpochSec - route.cachedAtEpochSec > maxAgeSec) return null
        return route
    }

    companion object {
        /** "Cached while online" - refresh at least daily when the backend is reachable. */
        const val DEFAULT_TTL_SEC: Long = 86_400L

        /** Route measurements go stale faster than fare configs (traffic changes). */
        const val DEFAULT_ROUTE_TTL_SEC: Long = 3_600L
    }
}
