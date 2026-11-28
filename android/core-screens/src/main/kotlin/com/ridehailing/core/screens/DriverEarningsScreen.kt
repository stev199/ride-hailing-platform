package com.ridehailing.core.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridehailing.core.data.wallet.EarningsAggregator

/**
 * Driver earnings tab (P7-4): today/week summary split CASH vs WALLET, per-trip
 * breakdown with derived commission (fare - capture credit; pending rides show an
 * honest "not credited yet" line), payout v1 = read-only note (the payout queue is
 * admin/provider work, no payout API exists yet).
 */
@Composable
fun DriverEarningsScreen(modifier: Modifier = Modifier) {
    val vm: DriverEarningsViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .semantics { testTag = "earnings_screen" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.signedIn) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.earnings_sign_in_prompt),
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@Column
        }

        if (state.loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "earnings_busy" },
            )
        }

        state.error?.let { e ->
            Text(
                text = "${e.code}: ${e.message}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "earnings_error" },
            )
        }

        val s = state.summary
        if (s != null) {
            SummaryCard(s)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "earnings_breakdown" },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.earnings_breakdown_title), style = MaterialTheme.typography.labelMedium)
                    if (s.trips.isEmpty()) {
                        Text(stringResource(R.string.earnings_empty), style = MaterialTheme.typography.bodySmall)
                    } else {
                        s.trips.forEachIndexed { index, t ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { testTag = "earnings_row" },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${t.city}/${t.rideType} · ${t.paymentMethod}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = when {
                                            t.paymentMethod == "CASH" -> stringResource(R.string.earnings_cash_label)
                                            t.walletCreditMinor == null -> stringResource(R.string.earnings_credit_pending)
                                            t.commissionMinor > 0 ->
                                                stringResource(R.string.earnings_trip_commission, t.commissionMinor.toString())
                                            else -> stringResource(R.string.earnings_no_commission)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    text = "${t.walletCreditMinor ?: t.fareMinor}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.earnings_payout_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SummaryCard(s: EarningsAggregator.Summary) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "earnings_summary" },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryRow(stringResource(R.string.earnings_today), s.todayCashMinor, s.todayWalletMinor, s.todayTrips)
            HorizontalDivider()
            SummaryRow(stringResource(R.string.earnings_week), s.weekCashMinor, s.weekWalletMinor, s.weekTrips)
        }
    }
}

@Composable
private fun SummaryRow(period: String, cashMinor: Long, walletMinor: Long, trips: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(period, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${stringResource(R.string.earnings_cash)} $cashMinor · ${stringResource(R.string.earnings_wallet)} $walletMinor",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.earnings_trips_count, trips.toString()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
