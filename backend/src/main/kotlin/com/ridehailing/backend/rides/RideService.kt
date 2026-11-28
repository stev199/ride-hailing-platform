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
    /** P7-2 ride money: wallet provider (null in graphs without the wallet work). */
    private val walletProvider: (() -> com.ridehailing.backend.wallet.WalletService)? = null,
    /** P7-2 commission source: the live wallet config section (provider-lambda pattern). */
    private val walletConfigProvider: (() -> com.ridehailing.backend.config.WalletConfig?)? = null,
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
        // P7-2: WALLET joins CASH - holds require a KNOWN amount, so WALLET is
        // upfront_locked-only (metered rides stay CASH until the meter settlement
        // lands, honest scope note). Units: the quote's minor amount is charged
        // against the POINTS wallet (v1 points-cent equivalence, spec §wallet).
        if (req.paymentMethod != "CASH" && req.paymentMethod != "WALLET") {
            return CreateResult.Invalid("paymentMethod", "payment_method_unavailable")
        }
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
        if (req.paymentMethod == "WALLET" && quote.mode != "upfront_locked") {
            return CreateResult.Invalid("paymentMethod", "wallet_requires_upfront_quote")
        }
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

    /** P5: rides assigned to a driver, newest first (driver trip history). */
    fun forDriver(driverId: String, limit: Int, offset: Int): List<Ride> =
        store.forDriver(driverId, limit.coerceIn(1, 100), offset.coerceAtLeast(0))

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

    /**
     * P7-5a: platform cancel (TRIPS_WRITE) - ANY non-terminal ride can be aborted by
     * the operator (incl. an in-progress trip), fee-free (the platform is not a
     * counterparty to its own cancellation). A pending WALLET hold is RELEASED
     * best-effort so the rider's money returns; a release that cannot happen
     * (hold already gone) is recorded in the event chain, never silently dropped.
     */
    fun adminCancel(actorId: String, rideId: String): CancelResult {
        val ride = store.byId(rideId) ?: return CancelResult.Error(404, "ride_not_found")
        if (ride.status.terminal) return CancelResult.Error(409, "cancel_not_allowed")
        val now = clock()
        ride.status.requireTransitionTo(RideStatus.CANCELLED_BY_ADMIN)
        var releaseOutcome = "not_applicable"
        if (ride.paymentMethod == "WALLET") {
            val wallet = walletProvider?.invoke()
            val released = wallet?.release(ride.riderId, ride.quoteAmountMinor, "ride", ride.id)
            releaseOutcome = when (released) {
                is com.ridehailing.backend.wallet.WalletService.HoldResult.Held -> "released"
                is com.ridehailing.backend.wallet.WalletService.HoldResult.Invalid ->
                    "release_failed:${released.code}"
                null -> "wallet_absent"
            }
        }
        val cancelled = store.updateStatus(rideId, RideStatus.CANCELLED_BY_ADMIN, now.epochSecond, 0L)!!
        eventRecorder?.append(
            "ride.cancelled",
            mapOf(
                "rideId" to rideId,
                "riderId" to ride.riderId,
                "cancelFeeMinor" to "0",
                "by" to "admin",
                "actorId" to actorId,
                "walletRelease" to releaseOutcome,
            ),
        )
        return CancelResult.Cancelled(cancelled)
    }

    // ---------- P5: driver duty + dispatch (spec delivery plan 5) ----------

    /**
     * Duty registry (online + last known position). HONEST SCOPE: per-process
     * (restart clears it; drivers re-toggle) - persistence lands with the Phase 7
     * store work; every transition is audited to the event chain either way.
     */
    data class DutyState(
        val online: Boolean,
        val lat: Double,
        val lon: Double,
        val updatedAtEpochSec: Long,
    )

    private val duty = java.util.concurrent.ConcurrentHashMap<String, DutyState>()

    sealed class DutyResult {
        data class Set(val state: DutyState) : DutyResult()
        data class Error(val status: Int, val code: String) : DutyResult()
    }

    fun setDuty(driver: User, online: Boolean, lat: Double, lon: Double): DutyResult {
        if (driver.role != Role.DRIVER) return DutyResult.Error(403, "driver_role_required")
        if (!lat.isFinite() || !lon.isFinite() || kotlin.math.abs(lat) > 90.0 || kotlin.math.abs(lon) > 180.0) {
            return DutyResult.Error(400, "invalid_coordinates")
        }
        val state = DutyState(online, lat, lon, clock().epochSecond)
        if (online) duty[driver.id] = state else duty.remove(driver.id)
        eventRecorder?.append(
            "driver.duty",
            mapOf(
                "driverId" to driver.id,
                "online" to online.toString(),
                "lat" to lat.toString(),
                "lon" to lon.toString(),
            ),
        )
        return DutyResult.Set(state)
    }

    fun dutyOf(driverId: String): DutyState? = duty[driverId]

    /**
     * P6-4 admin live map: a consistent copy of the duty registry (driverId ->
     * DutyState). Only ONLINE drivers are present by construction (the registry
     * removes on duty-off); the copy isolates callers from concurrent mutation.
     */
    fun dutySnapshot(): Map<String, DutyState> = java.util.Collections.unmodifiableMap(java.util.HashMap(duty))

    /** Dispatch candidate row: the ride + straight-line distance to the driver. */
    data class AvailableRide(val ride: Ride, val distanceMeters: Double)

    sealed class AvailableResult {
        data class Ok(val rides: List<AvailableRide>) : AvailableResult()
        data class Error(val status: Int, val code: String) : AvailableResult()
    }

    /** Nearby REQUESTED rides, closest first; duty must be ON (409 duty_offline). */
    fun availableRides(driver: User, limit: Int = 20): AvailableResult {
        if (driver.role != Role.DRIVER) return AvailableResult.Error(403, "driver_role_required")
        val d = duty[driver.id] ?: return AvailableResult.Error(409, "duty_offline")
        val rides = store.available(limit.coerceIn(1, 50))
        val sorted = rides
            .map { AvailableRide(it, FareMath.haversineMeters(d.lat, d.lon, it.pickupLat, it.pickupLon)) }
            .sortedBy { it.distanceMeters }
        return AvailableResult.Ok(sorted)
    }

    sealed class DriverActionResult {
        data class Ok(val ride: Ride) : DriverActionResult()
        data class Error(val status: Int, val code: String) : DriverActionResult()
    }

    /**
     * ACCEPT: ATOMIC single-winner claim (REQUESTED -> ACCEPTED + driver). A ride past
     * its quote validity is lazily EXPIRED first, so a stale board entry can never be
     * claimed (409 ride_expired). Two racing drivers: exactly one wins (spec edge).
     */
    fun accept(driver: User, rideId: String): DriverActionResult {
        if (driver.role != Role.DRIVER) return DriverActionResult.Error(403, "driver_role_required")
        val ride = store.byId(rideId) ?: return DriverActionResult.Error(404, "ride_not_found")
        val now = clock()
        if (ride.status == RideStatus.REQUESTED && now.epochSecond > ride.quoteValidUntilEpochSec) {
            ride.status.requireTransitionTo(RideStatus.EXPIRED)
            store.updateStatus(rideId, RideStatus.EXPIRED, now.epochSecond)
            eventRecorder?.append("ride.expired", mapOf("rideId" to rideId, "by" to "accept_raced_expiry"))
            return DriverActionResult.Error(409, "ride_expired")
        }
        // P7-2: WALLET rides hold the upfront-locked fare server-side AT ACCEPT
        // (spec "wallet holds happen server-side at confirmation"). The hold is
        // attempted BEFORE the single-winner claim: a rider without funds never
        // burns the race window (409 keeps the ride on the board for funded drivers).
        if (ride.paymentMethod == "WALLET") {
            val wallet = walletProvider?.invoke()
            val held = wallet?.hold(ride.riderId, ride.quoteAmountMinor, "ride", ride.id)
            if (wallet == null || held !is com.ridehailing.backend.wallet.WalletService.HoldResult.Held) {
                return DriverActionResult.Error(409, "wallet_hold_failed")
            }
        }
        val claimed = store.claimForDriver(rideId, driver.id, now.epochSecond)
            ?: return DriverActionResult.Error(409, "already_accepted")
        eventRecorder?.append(
            "ride.accepted",
            mapOf("rideId" to rideId, "driverId" to driver.id, "riderId" to ride.riderId),
        )
        return DriverActionResult.Ok(claimed)
    }

    /**
     * Driver-owned trip actions (arrive/start/complete): ONLY the assigned driver,
     * ONLY legal machine transitions (in-code enforcement, P4-2 map). Completion is
     * event-audited; the CASH settlement posts in Phase 7 (wallet holds + fees).
     */
    fun driverAction(driver: User, rideId: String, target: RideStatus): DriverActionResult {
        if (driver.role != Role.DRIVER) return DriverActionResult.Error(403, "driver_role_required")
        val ride = store.byId(rideId) ?: return DriverActionResult.Error(404, "ride_not_found")
        if (ride.driverId != driver.id) {
            // Unassigned rides stay invisible (404); another driver's ride is 403.
            return if (ride.driverId == null) {
                DriverActionResult.Error(404, "ride_not_found")
            } else {
                DriverActionResult.Error(403, "not_your_ride")
            }
        }
        val now = clock()
        try {
            ride.status.requireTransitionTo(target)
        } catch (_: IllegalArgumentException) {
            // requireTransitionTo throws require() -> IllegalArgumentException (409).
            return DriverActionResult.Error(409, "illegal_transition")
        }
        val updated = store.updateStatus(rideId, target, now.epochSecond)
            ?: return DriverActionResult.Error(404, "ride_not_found")
        // P7-2: WALLET completion captures the held fare - driver credited minus the
        // configured commission, platform fee wallet credited, ONE atomic transaction.
        // The hold was taken at accept; a hold that is somehow too small surfaces as
        // a money event (the trip itself still completes - the fare debt is tracked
        // by the ledger, never by blocking a finished ride).
        if (target == RideStatus.COMPLETED && ride.paymentMethod == "WALLET") {
            walletProvider?.invoke()?.let { wallet ->
                val cfgNow = walletConfigProvider?.invoke()
                val commissionBps = cfgNow?.commissionBps ?: 0
                when (val c = wallet.captureWithCommission(ride.riderId, driver.id, ride.quoteAmountMinor, commissionBps, "ride.completed", ride.id)) {
                    is com.ridehailing.backend.wallet.WalletService.HoldResult.Held ->
                        eventRecorder?.append(
                            "money.captured",
                            mapOf("rideId" to rideId, "amountMinor" to ride.quoteAmountMinor.toString(), "commissionBps" to commissionBps.toString()),
                        )
                    is com.ridehailing.backend.wallet.WalletService.HoldResult.Invalid ->
                        eventRecorder?.append(
                            "money.capture_failed",
                            mapOf("rideId" to rideId, "code" to c.code, "amountMinor" to ride.quoteAmountMinor.toString()),
                        )
                }
            }
        }
        eventRecorder?.append(
            "ride.${target.name.lowercase()}",
            mapOf("rideId" to rideId, "driverId" to driver.id, "riderId" to ride.riderId),
        )
        return DriverActionResult.Ok(updated)
    }

    companion object {
        /** Platform cash account in the ledger (fees converge here; payout flow Phase 7). */
        const val PLATFORM_ACCOUNT = "platform"
    }
}
