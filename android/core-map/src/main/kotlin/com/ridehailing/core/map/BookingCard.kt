package com.ridehailing.core.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.ridehailing.core.data.db.PlaceEntity
import com.ridehailing.core.data.db.RideStatus
import java.util.Locale

private fun money(major: Double, currency: String): String =
    String.format(Locale.US, "%.2f %s", major, currency)

private fun moneyMinor(minor: Long, currency: String): String =
    String.format(Locale.US, "%.2f %s", minor / 100.0, currency)

private fun rideTypeLabelRes(key: String): Int = when (key) {
    "standard" -> R.string.map_ride_standard
    "van" -> R.string.map_ride_van
    "lux" -> R.string.map_ride_lux
    else -> 0
}

@Composable
private fun breakdownLabel(key: String): String = when (key) {
    "base" -> stringResource(R.string.booking_breakdown_base)
    "distance" -> stringResource(R.string.booking_breakdown_distance)
    "time" -> stringResource(R.string.booking_breakdown_time)
    "booking_fee" -> stringResource(R.string.booking_breakdown_booking_fee)
    else -> key
}

/**
 * P4-4 rider booking card. PICKUP/DROP-OFF rows are tappable (they set the assign
 * target); tapping a SEARCH RESULT or RECENT row assigns it to the active target.
 * The estimate card shows the range + PERMANENT disclaimer (spec §pricing) + the fee
 * breakdown + route info; payment lists cash enabled and wallet/points disabled
 * (Phase 7). Server errors surface VERBATIM (B-04 policy).
 */
@Composable
fun BookingCard(
    state: BookingUiState,
    recents: List<PlaceEntity>,
    onPlace: (BookingPlace) -> Unit,
    onAssignTarget: (AssignTarget) -> Unit,
    onSelectRideType: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onBook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.booking_title), style = MaterialTheme.typography.titleSmall)

            val pickupText = state.pickup?.let {
                stringResource(R.string.booking_pickup_label, it.name)
            } ?: stringResource(R.string.booking_pickup_empty)
            Text(
                text = pickupText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAssignTarget(AssignTarget.PICKUP) }
                    .semantics { testTag = "booking_pickup" },
            )
            val dropoffText = state.dropoff?.let {
                stringResource(R.string.booking_dropoff_label, it.name)
            } ?: stringResource(R.string.booking_dropoff_empty)
            Text(
                text = dropoffText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAssignTarget(AssignTarget.DROPOFF) }
                    .semantics { testTag = "booking_dropoff" },
            )
            Text(
                text = stringResource(
                    if (state.assigning == AssignTarget.PICKUP) {
                        R.string.booking_hint_pickup_next
                    } else {
                        R.string.booking_hint_dropoff_next
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { testTag = "booking_assign_hint" },
            )

            if (recents.isNotEmpty()) {
                recents.take(3).forEach { place ->
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlace(BookingPlace(place.name, place.lat, place.lng)) }
                            .semantics { testTag = "booking_recent_${place.placeId}" },
                    )
                }
            }

            if (state.estimating) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "booking_estimating" },
                )
            }

            if (state.estimates.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.estimates.forEach { est ->
                        val res = rideTypeLabelRes(est.rideType)
                        FilterChip(
                            selected = state.selectedRideType == est.rideType,
                            onClick = { onSelectRideType(est.rideType) },
                            label = {
                                Text(if (res != 0) stringResource(res) else est.rideType)
                            },
                            modifier = Modifier.semantics { testTag = "booking_ride_${est.rideType}" },
                        )
                    }
                }

                val selected = state.estimates.firstOrNull { it.rideType == state.selectedRideType }
                    ?: state.estimates.first()
                Text(
                    text = stringResource(
                        R.string.booking_estimate_range,
                        money(selected.lowMajor, selected.currency),
                        money(selected.highMajor, selected.currency),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { testTag = "booking_estimate_range" },
                )
                if (state.routeDistanceKm != null && state.routeDurationMin != null) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "%.1f km · %.0f min",
                            state.routeDistanceKm,
                            state.routeDurationMin,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { testTag = "booking_route_info" },
                    )
                }
                Column(
                    modifier = Modifier.semantics { testTag = "booking_breakdown" },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    selected.breakdown.forEach { line ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = breakdownLabel(line.key),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = money(line.amountMajor, selected.currency),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (selected.minimumApplied) {
                        Text(
                            text = stringResource(R.string.booking_breakdown_minimum),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.booking_breakdown_total),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = money(selected.totalMajor, selected.currency),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                // PERMANENT rider-facing disclaimer (spec §pricing) - never conditional.
                Text(
                    text = stringResource(R.string.booking_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "booking_disclaimer" },
                )
                if (state.offlineEstimate) {
                    Text(
                        text = stringResource(R.string.booking_offline_badge),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.semantics { testTag = "booking_offline_badge" },
                    )
                }
            }

            if (state.estimateError != null) {
                Text(
                    text = stringResource(R.string.booking_estimate_error, state.estimateError),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "booking_estimate_error" },
                )
            }

            Text(
                text = stringResource(R.string.booking_payment_cash),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { testTag = "booking_payment_cash" },
            )
            Text(
                text = stringResource(R.string.booking_wallet_disabled),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { testTag = "booking_wallet_disabled" },
            )

            TextField(
                value = state.note,
                onValueChange = onNoteChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.booking_note_hint)) },
                modifier = Modifier.fillMaxWidth().semantics { testTag = "booking_note" },
            )

            Button(
                onClick = onBook,
                enabled = state.pickup != null && state.dropoff != null &&
                    state.selectedRideType != null && !state.booking,
                modifier = Modifier.semantics { testTag = "booking_book" },
            ) {
                Text(stringResource(R.string.booking_book))
            }

            if (state.bookingError != null) {
                Text(
                    text = stringResource(R.string.booking_error, state.bookingError),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "booking_error" },
                )
            }
        }
    }
}

private fun statusLabelRes(status: RideStatus): Int = when (status) {
    RideStatus.REQUESTED -> R.string.trip_status_REQUESTED
    RideStatus.OFFERED -> R.string.trip_status_OFFERED
    RideStatus.ACCEPTED -> R.string.trip_status_ACCEPTED
    RideStatus.IN_PROGRESS -> R.string.trip_status_IN_PROGRESS
    RideStatus.COMPLETED -> R.string.trip_status_COMPLETED
    RideStatus.CANCELLED -> R.string.trip_status_CANCELLED
}

/**
 * P4-4 trip card: local status truth + the server-SIGNED final quote once synced
 * (amount vs the estimate delta, mode, validity window + expiry), and cancel for
 * REQUESTED rides (server owns the rules; errors verbatim).
 */
@Composable
fun TripCard(
    state: BookingUiState,
    onCancel: () -> Unit,
    onDismissCancelError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trip = state.trip ?: return
    val selected = state.estimates.firstOrNull { it.rideType == state.selectedRideType }
        ?: state.estimates.firstOrNull()
    Card(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.trip_title), style = MaterialTheme.typography.titleSmall)
            trip.status?.let {
                Text(
                    text = stringResource(statusLabelRes(it)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { testTag = "trip_status" },
                )
            }
            if (!trip.syncedToServer && !trip.cancelled) {
                Text(
                    text = stringResource(R.string.trip_pending_sync),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "trip_pending_sync" },
                )
            }
            trip.fareMinor?.let { fare ->
                Text(
                    text = stringResource(
                        R.string.trip_fare,
                        moneyMinor(fare, trip.currency ?: ""),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { testTag = "trip_fare" },
                )
                if (selected != null) {
                    val delta = fare / 100.0 - selected.totalMajor
                    Text(
                        text = stringResource(
                            R.string.trip_quote_vs_estimate,
                            String.format(Locale.US, "%+.2f %s", delta, selected.currency),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { testTag = "trip_quote_vs_estimate" },
                    )
                }
            }
            trip.quoteMode?.let { mode ->
                val modeText = if (mode == "metered_capped") {
                    stringResource(R.string.trip_quote_mode_metered)
                } else {
                    stringResource(R.string.trip_quote_mode_upfront)
                }
                Text(
                    text = modeText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "trip_quote_mode" },
                )
            }
            trip.validUntilEpochSec?.let { until ->
                val minutesLeft = ((until - System.currentTimeMillis() / 1000) / 60).coerceAtLeast(0)
                Text(
                    text = stringResource(R.string.trip_valid_until, minutesLeft),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "trip_valid_until" },
                )
                if (trip.quoteExpired) {
                    Text(
                        text = stringResource(R.string.trip_quote_expired),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { testTag = "trip_quote_expired" },
                    )
                }
            }
            if (trip.cancelled) {
                Text(
                    text = stringResource(R.string.trip_cancelled),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { testTag = "trip_cancelled" },
                )
            } else {
                Button(
                    onClick = {
                        onDismissCancelError()
                        onCancel()
                    },
                    enabled = trip.status == RideStatus.REQUESTED && !trip.cancelling,
                    modifier = Modifier.semantics { testTag = "trip_cancel" },
                ) {
                    Text(stringResource(R.string.trip_cancel))
                }
            }
            if (trip.cancelError != null) {
                Text(
                    text = stringResource(R.string.trip_cancel_error, trip.cancelError),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "trip_cancel_error" },
                )
            }
        }
    }
}
