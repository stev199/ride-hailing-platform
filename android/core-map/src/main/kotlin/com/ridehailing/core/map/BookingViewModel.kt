package com.ridehailing.core.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridehailing.core.data.db.RideStatus
import com.ridehailing.core.data.di.ClockMs
import com.ridehailing.core.data.network.RideTypeEstimateDto
import com.ridehailing.core.data.rides.EstimateOutcome
import com.ridehailing.core.data.rides.RideDraft
import com.ridehailing.core.data.rides.RideRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A place the rider assigned to pickup/dropoff (from search results, recents or saved). */
data class BookingPlace(val name: String, val lat: Double, val lng: Double)

/** Which slot the next tapped place fills. */
enum class AssignTarget { PICKUP, DROPOFF }

/** One breakdown line - STABLE key, the composable renders the localized label. */
data class BreakdownLineUi(val key: String, val amountMajor: Double)

/** Normalized per-ride-type estimate (identical shape for online and offline sources). */
data class RideTypeEstimateUi(
    val rideType: String,
    val currency: String,
    val lowMajor: Double,
    val highMajor: Double,
    val surgeReason: String?,
    val totalMajor: Double,
    val minimumApplied: Boolean,
    val breakdown: List<BreakdownLineUi>,
)

/**
 * Pure mapping from wire/offline estimates to UI rows (unit-tested without Android).
 * The PERMANENT disclaimer is rendered unconditionally by the composable (spec §pricing:
 * the estimate is never the final price), so it carries no state here.
 */
object BookingUiMapper {

    fun fromOnline(dto: RideTypeEstimateDto): RideTypeEstimateUi = RideTypeEstimateUi(
        rideType = dto.rideType,
        currency = dto.currency,
        lowMajor = dto.lowTotal,
        highMajor = dto.highTotal,
        surgeReason = dto.surgeReason,
        totalMajor = dto.breakdown.total,
        minimumApplied = dto.breakdown.minimumApplied,
        breakdown = listOf(
            BreakdownLineUi("base", dto.breakdown.base),
            BreakdownLineUi("distance", dto.breakdown.distanceCost),
            BreakdownLineUi("time", dto.breakdown.timeCost),
            BreakdownLineUi("booking_fee", dto.breakdown.bookingFee),
        ),
    )

    fun fromOffline(row: com.ridehailing.core.data.pricing.RideTypeEstimate): RideTypeEstimateUi =
        RideTypeEstimateUi(
            rideType = row.rideType,
            currency = row.currency,
            lowMajor = row.lowTotal,
            highMajor = row.highTotal,
            surgeReason = row.surgeReason,
            totalMajor = row.breakdown.total,
            minimumApplied = row.breakdown.minimumApplied,
            breakdown = listOf(
                BreakdownLineUi("base", row.breakdown.base),
                BreakdownLineUi("distance", row.breakdown.distanceCost),
                BreakdownLineUi("time", row.breakdown.timeCost),
                BreakdownLineUi("booking_fee", row.breakdown.bookingFee),
            ),
        )
}

data class BookingUiState(
    val pickup: BookingPlace? = null,
    val dropoff: BookingPlace? = null,
    val assigning: AssignTarget = AssignTarget.PICKUP,
    val estimates: List<RideTypeEstimateUi> = emptyList(),
    val selectedRideType: String? = null,
    /** True when the displayed estimate came from the on-device estimator (offline path). */
    val offlineEstimate: Boolean = false,
    val estimating: Boolean = false,
    val estimateError: String? = null,
    /** Live route measurement behind the estimate (route preview v1, km + minutes). */
    val routeDistanceKm: Double? = null,
    val routeDurationMin: Double? = null,
    val note: String = "",
    val booking: Boolean = false,
    val bookingError: String? = null,
    /** Booking accepted locally but NOT yet acked by the backend (outbox pending). */
    val bookingOfflineQueued: Boolean = false,
    val bookedRideId: String? = null,
    val trip: TripUiState? = null,
)

data class TripUiState(
    val rideId: String,
    val status: RideStatus? = null,
    val syncedToServer: Boolean = false,
    val fareMinor: Long? = null,
    val currency: String? = null,
    val quoteMode: String? = null,
    val validUntilEpochSec: Long? = null,
    val quoteExpired: Boolean = false,
    val cancelling: Boolean = false,
    val cancelError: String? = null,
    /** Local truth flipped to CANCELLED (server ack or local cancel). */
    val cancelled: Boolean = false,
)

/**
 * P4-4 rider booking state machine (booking + trip in one VM: the trip screen is a
 * state of the same flow, no nav-arg plumbing). Flow: assign pickup -> assign dropoff
 * (tap search results / recents) -> estimate card (server-first, offline fallback) ->
 * ride-type chip -> book -> trip card (signed quote vs estimate, cancel REQUESTED-only).
 */
@HiltViewModel
class BookingViewModel @Inject constructor(
    private val rides: RideRepository,
    @ClockMs private val clockMs: () -> Long,
) : ViewModel() {

    private val _state = MutableStateFlow(BookingUiState())
    val state: StateFlow<BookingUiState> = _state.asStateFlow()

    /** A place card (search result, recent, saved) was tapped. */
    fun onPlaceTapped(place: BookingPlace) {
        val target = _state.value.assigning
        _state.update {
            if (target == AssignTarget.PICKUP) {
                it.copy(pickup = place, assigning = AssignTarget.DROPOFF)
            } else {
                it.copy(dropoff = place, assigning = AssignTarget.PICKUP)
            }
        }
        if (_state.value.pickup != null && _state.value.dropoff != null) refreshEstimate()
    }

    fun setAssignTarget(target: AssignTarget) = _state.update { it.copy(assigning = target) }

    fun setNote(raw: String) = _state.update { it.copy(note = raw.take(240)) }

    fun selectRideType(rideType: String) = _state.update { it.copy(selectedRideType = rideType) }

    fun clearEstimateError() = _state.update { it.copy(estimateError = null) }

    fun clearBookingError() = _state.update { it.copy(bookingError = null) }

    fun clearCancelError() = _state.update { it.copy(trip = it.trip?.copy(cancelError = null)) }

    /** Server-first estimate; ANY failure falls back to the on-device estimator. */
    fun refreshEstimate() {
        val pickup = _state.value.pickup ?: return
        val dropoff = _state.value.dropoff ?: return
        _state.update { it.copy(estimating = true, estimateError = null) }
        viewModelScope.launch {
            when (val out = rides.estimate(
                pickupLat = pickup.lat, pickupLon = pickup.lng,
                dropoffLat = dropoff.lat, dropoffLon = dropoff.lng,
                city = MapConfig.DEFAULT_CITY, rideType = null,
            )) {
                is EstimateOutcome.Online -> {
                    val rows = out.response.estimates.map(BookingUiMapper::fromOnline)
                    _state.update { s ->
                        s.copy(
                            estimating = false,
                            offlineEstimate = false,
                            estimates = rows,
                            routeDistanceKm = out.response.distanceMeters / 1000.0,
                            routeDurationMin = out.response.durationSeconds / 60.0,
                            selectedRideType = resolveSelection(s.selectedRideType, rows),
                        )
                    }
                }
                is EstimateOutcome.Offline -> {
                    val rows = out.result.estimates.map(BookingUiMapper::fromOffline)
                    _state.update { s ->
                        s.copy(
                            estimating = false,
                            offlineEstimate = true,
                            estimates = rows,
                            routeDistanceKm = out.result.distanceMeters / 1000.0,
                            routeDurationMin = out.result.durationSeconds / 60.0,
                            selectedRideType = resolveSelection(s.selectedRideType, rows),
                        )
                    }
                }
                is EstimateOutcome.Unavailable ->
                    _state.update { it.copy(estimating = false, estimateError = out.error) }
            }
        }
    }

    private fun resolveSelection(current: String?, rows: List<RideTypeEstimateUi>): String? =
        current?.takeIf { sel -> rows.any { it.rideType == sel } } ?: rows.firstOrNull()?.rideType

    /** BOOK: local truth first, outbox drain immediately; offline bookings queue. */
    fun book() {
        val pickup = _state.value.pickup ?: return
        val dropoff = _state.value.dropoff ?: return
        val rideType = _state.value.selectedRideType
            ?: _state.value.estimates.firstOrNull()?.rideType
            ?: return
        if (_state.value.booking) return
        _state.update { it.copy(booking = true, bookingError = null, bookingOfflineQueued = false) }
        viewModelScope.launch {
            try {
                val rideId = rides.book(
                    RideDraft(
                        pickupLabel = pickup.name, pickupLat = pickup.lat, pickupLng = pickup.lng,
                        dropoffLabel = dropoff.name, dropoffLat = dropoff.lat, dropoffLng = dropoff.lng,
                        city = MapConfig.DEFAULT_CITY, rideType = rideType,
                        note = _state.value.note.ifBlank { null },
                    ),
                )
                val entity = rides.observe(rideId).first()
                _state.update {
                    it.copy(
                        booking = false,
                        bookedRideId = rideId,
                        bookingOfflineQueued = entity?.serverId == null,
                        trip = TripUiState(rideId = rideId),
                    )
                }
                observeTrip(rideId)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(booking = false, bookingError = t.message ?: "booking_failed")
                }
            }
        }
    }

    /** Long-lived local-truth observation + one-shot signed-quote fetch once synced. */
    private fun observeTrip(rideId: String) {
        viewModelScope.launch {
            rides.observe(rideId).collect { entity ->
                if (entity == null) return@collect
                val syncedNow = entity.serverId != null && _state.value.trip?.quoteMode == null
                _state.update {
                    it.copy(
                        trip = (it.trip ?: TripUiState(rideId = rideId)).copy(
                            status = entity.status,
                            syncedToServer = entity.serverId != null,
                            fareMinor = entity.fareMinor,
                            currency = entity.currency,
                            cancelled = entity.status == RideStatus.CANCELLED,
                        ),
                    )
                }
                if (syncedNow) {
                    val remote = rides.remoteRide(rideId)
                    if (remote != null) {
                        val nowSec = clockMs() / 1000
                        _state.update {
                            it.copy(
                                trip = it.trip?.copy(
                                    quoteMode = remote.quote.mode,
                                    validUntilEpochSec = remote.quote.validUntilEpochSec,
                                    quoteExpired = nowSec >= remote.quote.validUntilEpochSec &&
                                        remote.status == "REQUESTED",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Cancel is online-only v1 (server owns the REQUESTED-only + fee rules). */
    fun cancelTrip() {
        val rideId = _state.value.trip?.rideId ?: return
        if (_state.value.trip?.cancelling == true) return
        _state.update { it.copy(trip = it.trip?.copy(cancelling = true, cancelError = null)) }
        viewModelScope.launch {
            rides.cancel(rideId)
                .onFailure { t ->
                    _state.update {
                        it.copy(
                            trip = it.trip?.copy(
                                cancelling = false,
                                cancelError = t.message ?: "cancel_failed",
                            ),
                        )
                    }
                }
            // On success the local-truth flow flips status -> CANCELLED (cancelled flag).
        }
    }
}
