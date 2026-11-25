package com.ridehailing.backend.rides

import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.config.SignedConfigBundle
import com.ridehailing.backend.fares.FareMath
import com.ridehailing.backend.fares.OsrmRouteSource
import com.ridehailing.backend.ledger.LedgerLeg
import com.ridehailing.backend.ledger.LedgerStore
import java.time.Instant

/**
 * P4-2 ride service: booking (server-SIGNED final quote per spec §pricing),
 * rider-owned reads with lazy quote-expiry transition, pre-accept cancellation
 * with the configured cancel fee posted to the double-entry ledger.
 *
 * Active v1 surface (Phase 4 = rider booking happy path):
 * - create: RIDER role only (drivers drive, admins administer; 403 booking_role_required).
 *   CASH only (wallet/points become bookable in Phase 7; 400 payment_method_unavailable).
 * - get/list: rider-owned rows; a REQUESTED ride past its quote validity transitions
 *   to EXPIRED on read (deterministic, clock-injected; event ride.expired).
 * - cancel: REQUESTED only (409 cancel_not_allowed otherwise; post-accept cancel
 *   rules arrive with the driver app in Phase 5). Cancel fee from the ride type's
 *   configured cancelFee posts to the ledger (rider CASH wallet may go negative per
 *   the P1-5 schema; the fee is the honest record of what the rider owes; Phase 7
 *   reconciliation owns settlement).
 */
class RideService(
    private val store: RideStore,
    private val fares: () -> com.ridehailing.backend.fares.FareConfig,
    private val bundle: SignedConfigBundle,
    private val ledger: LedgerStore?,
    private val osrm: OsrmRouteSource? = null,
    private val clock: () -> Instant = Instant::now,
    private val eventRecorder: EventRecorder? = null,
) {
    fun interface EventRecorder {
        fun append(type: String, payload: Map<String, String?>)
    }

    data class CreateRequest(
        val pickupLat: Double,
        val pickupLon: Double,
        val dropoffLat: Double,
        val dropoffLon: Double,
        val city: String,
        val rideType: String,
        val paymentMethod: String,
        val note: String? = null,
        val pickupName: String? = null,
        val dropoffName: String? = null,
    )

    sealed class CreateResult {
        data class Created(val ride: Ride) : CreateResult()
        data class Invalid(val field: String, val code: String) : CreateResult()
        data class Forbidden(val code: String) : CreateResult()
    }

    sealed class CancelResult {
        data class Cancelled(val ride: Ride) : CancelResult()
        data class Error(val status: Int, val code: String) : CancelResult()
    }

    fun create(rider: User, req: CreateRequest): CreateResult {
        if (rider.role != Role.RIDER) return CreateResult.Forbidden("booking_role_required")
        val coords = listOf(req.pickupLat, req.pickupLon, req.dropoffLat, req.dropoffLon)
        if (coords.any { !it.isFinite() || kotlin.math.abs(it) > 180.0 }) {
            return CreateResult.Invalid("coordinates", "invalid_coordinates")
        }
        val cfg = fares()
        val cityFares = cfg.cities[req.city] ?: return CreateResult.Invalid("city", "unknown_city")
        val typeFares = cityFares.rideTypes[req.rideType] ?: return CreateResult.Invalid("rideType", "unknown_ride_type")
        if (req.paymentMethod != "CASH") return CreateResult.Invalid("paymentMethod", "payment_method_unavailable")
        if (req.note != null && req.note.length > 500) return CreateResult.Invalid("note", "note_too_long")

        val now = clock()
        // Route input: OSRM when reachable (null/throw -> degrade to the calibrated
        // straight-line fallback - the rider must never see a 5xx because a geo
        // service is down; identical contract to the P4-1 estimate endpoint).
        val route: FareMath.RouteInput = try {
            val r = osrm?.route(req.pickupLat, req.pickupLon, req.dropoffLat, req.dropoffLon)
            if (r != null) FareMath.RouteInput(r.distanceMeters, r.durationSeconds)
            else null
        } catch (_: Exception) {
            null
        } ?: FareMath.straightLineRoute(
            req.pickupLat, req.pickupLon, req.dropoffLat, req.dropoffLon,
            cfg.straightLine.detourFactor, cfg.straightLine.assumedAvgSpeedKmh,
        )
        val quote = RideQuote.forRide(
            bundle = bundle,
            riderId = rider.id,
            city = req.city,
            rideType = req.rideType,
            currency = cityFares.currency,
            params = typeFares.toParams(),
            route = route,
            pickupLat = req.pickupLat,
            pickupLon = req.pickupLon,
            dropoffLat = req.dropoffLat,
            dropoffLon = req.dropoffLon,
            policy = cfg.quote,
            now = now,
        )
        val ride = Ride(
            id = com.ridehailing.backend.auth.Ids.newId(),
            riderId = rider.id,
            city = req.city,
            rideType = req.rideType,
            status = RideStatus.REQUESTED,
            paymentMethod = req.paymentMethod,
            note = req.note?.trim()?.ifEmpty { null },
            pickupLat = req.pickupLat,
            pickupLon = req.pickupLon,
            dropoffLat = req.dropoffLat,
            dropoffLon = req.dropoffLon,
            pickupName = req.pickupName?.trim()?.ifEmpty { null },
            dropoffName = req.dropoffName?.trim()?.ifEmpty { null },
            quoteJson = quote.payloadCanonicalJson,
            quoteSignature = quote.signatureHex,
            quoteAmountMinor = quote.amountMinor,
            quoteCurrency = quote.currency,
            quoteMode = quote.mode,
            quoteMeteredCapMinor = quote.meteredCapMinor,
            quoteIssuedAtEpochSec = quote.issuedAtEpochSec,
            quoteValidUntilEpochSec = quote.validUntilEpochSec,
            cancelFeeMinor = 0,
            createdAtEpochSec = now.epochSecond,
            updatedAtEpochSec = now.epochSecond,
        )
        store.insert(ride)
        eventRecorder?.append(
            "ride.requested",
            mapOf(
                "rideId" to ride.id,
                "riderId" to rider.id,
                "city" to ride.city,
                "rideType" to ride.rideType,
                "amountMinor" to ride.quoteAmountMinor.toString(),
                "quoteMode" to ride.quoteMode,
            ),
        )
        return CreateResult.Created(ride)
    }

    /** Rider-owned read; lazily expires a REQUESTED ride past its quote validity. */
    fun getForRider(riderId: String, rideId: String): Ride? {
        val ride = store.byId(rideId) ?: return null
        if (ride.riderId != riderId) return null
        if (ride.status == RideStatus.REQUESTED && clock().epochSecond > ride.quoteValidUntilEpochSec) {
            val now = clock().epochSecond
            ride.status.requireTransitionTo(RideStatus.EXPIRED)
            val expired = store.updateStatus(rideId, RideStatus.EXPIRED, now) ?: return ride
            eventRecorder?.append(
                "ride.expired",
                mapOf("rideId" to rideId, "riderId" to riderId, "validUntilEpochSec" to ride.quoteValidUntilEpochSec.toString()),
            )
            return expired
        }
        return ride
    }

    fun listForRider(riderId: String, limit: Int, offset: Int): List<Ride> =
        store.forRider(riderId, limit.coerceIn(1, 100), offset.coerceAtLeast(0))

    fun cancel(riderId: String, rideId: String): CancelResult {
        val ride = store.byId(rideId) ?: return CancelResult.Error(404, "ride_not_found")
        if (ride.riderId != riderId) return CancelResult.Error(404, "ride_not_found")
        if (ride.status != RideStatus.REQUESTED) return CancelResult.Error(409, "cancel_not_allowed")
        val now = clock()
        ride.status.requireTransitionTo(RideStatus.CANCELLED_BY_RIDER)

        // Cancel fee from the CURRENT config for the booked ride type (the fee the
        // rider agreed to at booking; an admin price change mid-ride applies forward).
        val fee = fares().cities[ride.city]?.rideTypes?.get(ride.rideType)?.cancelFee ?: 0.0
        val feeMinor = if (fee > 0.0) Math.round(fee * 100.0) else 0L
        if (feeMinor > 0 && ledger != null) {
            // DEBIT the rider's cash wallet (CASH may go negative per the P1-5 schema),
            // CREDIT the platform cash account. Double-entry: legs sum to zero.
            val riderWallet = ledger.ensureWallet(riderId, currency = ride.quoteCurrency, allowNegative = true)
            val platformWallet = ledger.ensureWallet(PLATFORM_ACCOUNT, currency = ride.quoteCurrency, allowNegative = false)
            ledger.postTransaction(
                legs = listOf(
                    LedgerLeg(riderWallet.id, feeMinor, com.ridehailing.backend.ledger.Direction.DEBIT),
                    LedgerLeg(platformWallet.id, feeMinor, com.ridehailing.backend.ledger.Direction.CREDIT),
                ),
                refType = "ride.cancelled",
                refId = ride.id,
                reason = "cancellation fee for $ride.city/$ride.rideType (rider cancel before accept)",
            )
        }
        val cancelled = store.updateStatus(rideId, RideStatus.CANCELLED_BY_RIDER, now.epochSecond, feeMinor)!!
        eventRecorder?.append(
            "ride.cancelled",
            mapOf(
                "rideId" to rideId,
                "riderId" to riderId,
                "cancelFeeMinor" to feeMinor.toString(),
                "by" to "rider",
            ),
        )
        return CancelResult.Cancelled(cancelled)
    }

    companion object {
        /** Platform cash account in the ledger (fees converge here; payout flow Phase 7). */
        const val PLATFORM_ACCOUNT = "platform"
    }
}
