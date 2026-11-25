package com.ridehailing.core.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.ridehailing.core.data.rides.DriverAction
import java.util.Locale

private fun driverMoney(minor: Long, currency: String): String =
    String.format(Locale.US, "%.2f %s", minor / 100.0, currency)

private fun driverDistance(meters: Double?): String =
    meters?.let { String.format(Locale.US, "%.1f km", it / 1000.0) } ?: ""

/**
 * P5 duty card: server-backed online/offline toggle (the UI never lies - until the
 * backend acks, the switch stays off and the verbatim error shows).
 */
@Composable
fun DriverDutyCard(
    state: DriverUiState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (state.online) R.string.map_duty_online else R.string.map_duty_offline,
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { testTag = "duty_state" },
            )
            Switch(
                checked = state.online,
                onCheckedChange = onToggle,
                enabled = !state.dutySyncing,
                modifier = Modifier.semantics { testTag = "duty_toggle" },
            )
            if (state.dutyError != null) {
                Text(
                    text = stringResource(R.string.driver_duty_error, state.dutyError),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "duty_error" },
                )
            }
            Text(
                text = stringResource(R.string.driver_signin_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { testTag = "driver_signin_hint" },
            )
        }
    }
}

/**
 * P5 dispatch board (on duty): REQUESTED rides closest first; tap = single-winner
 * accept claim (409 already_accepted renders verbatim if another driver won).
 */
@Composable
fun DriverOffersCard(
    state: DriverUiState,
    onAccept: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.driver_offers_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.offers.isEmpty()) {
                Text(
                    text = stringResource(R.string.driver_no_offers),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "driver_no_offers" },
                )
            }
            state.offers.forEach { offer ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAccept(offer.id) }
                        .semantics { testTag = "offer_${offer.id}" },
                ) {
                    Text(
                        text = stringResource(R.string.driver_offer_pickup, offer.pickupName ?: "?"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(
                                R.string.driver_offer_meta,
                                offer.rideType,
                                driverMoney(offer.quote.amountMinor, offer.quote.currency),
                                driverDistance(offer.distanceMeters),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.driver_offer_accept),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            if (state.offersError != null) {
                Text(
                    text = stringResource(R.string.driver_offers_error, state.offersError),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "driver_offers_error" },
                )
            }
        }
    }
}

/**
 * P5 active trip card: driver-owned actions per machine state
 * (ACCEPTED -> arrive, ARRIVED -> start, IN_PROGRESS -> complete, COMPLETED shown).
 */
@Composable
fun DriverTripCard(
    state: DriverUiState,
    onAction: (DriverAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ride = state.activeRide ?: return
    Card(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.driver_trip_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = ride.status,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { testTag = "trip_status_driver" },
            )
            Text(
                text = stringResource(R.string.driver_trip_pickup, ride.pickupName ?: "?"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { testTag = "driver_trip_pickup" },
            )
            Text(
                text = stringResource(R.string.driver_trip_dropoff, ride.dropoffName ?: "?"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { testTag = "driver_trip_dropoff" },
            )
            Text(
                text = stringResource(
                    R.string.driver_trip_fare,
                    driverMoney(ride.quote.amountMinor, ride.quote.currency),
                    ride.quote.mode,
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { testTag = "driver_trip_fare" },
            )
            ride.note?.let {
                Text(
                    text = stringResource(R.string.driver_trip_note, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
            when (ride.status) {
                "ACCEPTED" -> Button(
                    onClick = { onAction(DriverAction.ARRIVE) },
                    enabled = !state.acting,
                    modifier = Modifier.semantics { testTag = "trip_arrive" },
                ) { Text(stringResource(R.string.driver_arrive)) }
                "ARRIVED" -> Button(
                    onClick = { onAction(DriverAction.START) },
                    enabled = !state.acting,
                    modifier = Modifier.semantics { testTag = "trip_start" },
                ) { Text(stringResource(R.string.driver_start)) }
                "IN_PROGRESS" -> Button(
                    onClick = { onAction(DriverAction.COMPLETE) },
                    enabled = !state.acting,
                    modifier = Modifier.semantics { testTag = "trip_complete" },
                ) { Text(stringResource(R.string.driver_complete)) }
                "COMPLETED" -> Text(
                    text = stringResource(R.string.driver_completed),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { testTag = "driver_completed" },
                )
            }
            if (state.actionError != null) {
                Text(
                    text = stringResource(R.string.driver_action_error, state.actionError),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "driver_action_error" },
                )
            }
        }
    }
}

/** Composed driver bottom card: duty, then trip (takes precedence), then the board. */
@Composable
fun DriverBottomCard(
    state: DriverUiState,
    onToggle: (Boolean) -> Unit,
    onAccept: (String) -> Unit,
    onAction: (DriverAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (state.activeRide != null) {
            DriverTripCard(state = state, onAction = onAction)
        } else if (state.online) {
            DriverOffersCard(state = state, onAccept = onAccept)
        } else {
            DriverDutyCard(state = state, onToggle = onToggle)
        }
    }
}
