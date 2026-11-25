package com.ridehailing.core.data.pricing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * P4-3 device-visible fare bundle - wire mirror of the backend config contract
 * (ConfigBundleDto + the `fares` section of the canonical config JSON). Field names
 * are byte-mirrors of the openapi; unknown keys are ignored (D46 pattern, ApiJson).
 *
 * SHAPE CONTRACT with the backend (FareConfig.kt): the device DTOs use THE SAME
 * nested shape (surge windows are objects, not flat fields) so the two can never
 * drift. The flat primitives exist only inside FareMath (the parity file) and are
 * mapped by [DeviceRideTypeFares.toParams] - same bridge as backend RideTypeFares.
 */
@Serializable
data class FareBundleEnvelopeDto(
    val version: Long,
    @SerialName("issuedAtEpochSec") val issuedAtEpochSec: Long,
    /** The EXACT canonical JSON string the Ed25519 signature covers - parsed, never re-serialized. */
    val config: String,
    val signature: String,
    val publicKey: String,
)

/**
 * Top-level canonical config JSON (ConfigService.configCanonicalJson): login methods +
 * the fares section, signed as ONE string. The device parses THIS and reads [fares];
 * loginMethods is carried as raw JSON until the device needs it (Phase 5 OTP UI).
 */
@Serializable
data class CanonicalConfigDto(
    val loginMethods: kotlinx.serialization.json.JsonObject? = null,
    val fares: DeviceFareConfig = DeviceFareConfig(),
)

@Serializable
data class DeviceFareConfig(
    val cities: Map<String, DeviceCityFares> = emptyMap(),
    val straightLine: DeviceStraightLineCalibration = DeviceStraightLineCalibration(),
    val quote: DeviceQuotePolicy = DeviceQuotePolicy(),
)

@Serializable
data class DeviceCityFares(
    val currency: String,
    val rideTypes: Map<String, DeviceRideTypeFares>,
)

@Serializable
data class DeviceRideTypeFares(
    val base: Double,
    val perKm: Double,
    val perMinute: Double,
    val minimumFare: Double,
    val bookingFee: Double = 0.0,
    val cancelFee: Double = 0.0,
    val waitFeePerMinute: Double = 0.0,
    val night: DeviceSurgeWindow = DeviceSurgeWindow(),
    val peak: DeviceSurgeWindow = DeviceSurgeWindow(),
)

@Serializable
data class DeviceSurgeWindow(
    val multiplier: Double = 1.0,
    val startHourUtc: Int = 0,
    val endHourUtc: Int = 0,
)

@Serializable
data class DeviceStraightLineCalibration(
    val detourFactor: Double = 1.3,
    val assumedAvgSpeedKmh: Double = 22.0,
)

@Serializable
data class DeviceQuotePolicy(
    val validitySeconds: Int = 90,
    val mode: String = "upfront_locked",
    val meteredCapMultiplier: Double = 1.5,
)

/** Bridge to the pure parity math (FareMath takes positional primitives only). */
fun DeviceRideTypeFares.toParams(): FareMath.RideTypeParams = FareMath.RideTypeParams(
    base = base, perKm = perKm, perMinute = perMinute, minimumFare = minimumFare,
    bookingFee = bookingFee, cancelFee = cancelFee, waitFeePerMinute = waitFeePerMinute,
    nightMultiplier = night.multiplier, nightStartHourUtc = night.startHourUtc, nightEndHourUtc = night.endHourUtc,
    peakMultiplier = peak.multiplier, peakStartHourUtc = peak.startHourUtc, peakEndHourUtc = peak.endHourUtc,
)

/**
 * Device-side bundle verification (B-01/A-10): Ed25519 over
 * "version|config|issuedAtEpochSec" - the same signature the backend signs and the
 * admin-web/tests verify. Returns:
 * - `true` / `false` when the platform HAS an Ed25519 provider (JVM 15+, Android 33+),
 * - `null` when the platform does NOT (Android < 33): verification is SKIPPED and the
 *   outcome is recorded as such in the cache - never treated as a failure. Full device
 *   verification for older APIs lands with the quote-verification work (Phase 7) where
 *   a real BC provider ships; anti-rollback (version check) protects versioning NOW.
 */
object FareBundleVerifier {

    fun verify(env: FareBundleEnvelopeDto): Boolean? = try {
        val pub = java.security.KeyFactory.getInstance("Ed25519")
            .generatePublic(java.security.spec.X509EncodedKeySpec(hexDecode(env.publicKey)))
        val sig = java.security.Signature.getInstance("Ed25519")
        sig.initVerify(pub)
        sig.update(bundleContent(env).toByteArray(Charsets.UTF_8))
        sig.verify(hexDecode(env.signature))
    } catch (_: java.security.NoSuchAlgorithmException) {
        null
    } catch (_: Exception) {
        false
    }

    fun bundleContent(env: FareBundleEnvelopeDto): String =
        "${env.version}|${env.config}|${env.issuedAtEpochSec}"

    fun hexDecode(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }
}
