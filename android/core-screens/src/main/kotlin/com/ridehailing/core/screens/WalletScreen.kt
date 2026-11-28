package com.ridehailing.core.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ridehailing.core.data.network.WalletHistoryItemDto
import java.util.Locale

/**
 * Rider wallet tab (P7-3): balance card (POINTS + held), limits/fees from the signed
 * config bundle (P7-1 wallet section), points transfer by phone, ledger history (first
 * page, newest-first server-side). Top-up v1 is admin credit only - the honest note
 * stays on screen until vouchers/cash agents land with the provider work.
 *
 * Money is minor units (integral points); server errors surface VERBATIM (code +
 * message) exactly like the auth surface - the backend is the authority.
 */
@Composable
fun WalletScreen(modifier: Modifier = Modifier) {
    val vm: WalletViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .semantics { testTag = "wallet_screen" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.signedIn) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.wallet_sign_in_prompt),
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@Column
        }

        if (state.loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "wallet_busy" },
            )
        }

        BalanceCard(state.balances?.balances?.get("POINTS"), state.balances?.heldMinor ?: 0L)

        LimitsCard(state.config)

        TransferCard(vm, state.transferInFlight, state.lastTransfer)

        state.error?.let { e ->
            Text(
                text = "${e.code}: ${e.message}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "wallet_error" },
            )
        }

        HistoryCard(state.history)

        Text(
            text = stringResource(R.string.wallet_topup_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BalanceCard(pointsMinor: Long?, heldMinor: Long) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "wallet_balance" },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.wallet_balance), style = MaterialTheme.typography.labelMedium)
            Text(
                text = "${pointsMinor ?: 0L} ${stringResource(R.string.wallet_points)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (heldMinor > 0L) {
                Text(
                    text = "${stringResource(R.string.wallet_held)}: $heldMinor ${stringResource(R.string.wallet_points)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "wallet_held" },
                )
            }
        }
    }
}

@Composable
private fun LimitsCard(config: com.ridehailing.core.data.network.DeviceWalletConfig?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "wallet_limits" },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.wallet_limits_title), style = MaterialTheme.typography.labelMedium)
            if (config == null) {
                Text(stringResource(R.string.wallet_limits_unavailable), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    text = stringResource(R.string.wallet_transfer_fee, percentLabel(config.transferFeeBps)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.wallet_min_transfer, config.minTransferMinor.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.wallet_daily_limit, config.dailyTransferLimitMinor.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TransferCard(
    vm: WalletViewModel,
    inFlight: Boolean,
    last: com.ridehailing.core.data.network.WalletTransferResultDto?,
) {
    var phone by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wallet_transfer_title), style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text(stringResource(R.string.wallet_transfer_to)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "wallet_transfer_phone" },
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.wallet_transfer_amount)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "wallet_transfer_amount" },
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.wallet_transfer_note)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "wallet_transfer_note" },
            )
            Button(
                onClick = {
                    val minor = amount.toLongOrNull() ?: 0L
                    vm.transfer(phone, minor, note)
                },
                enabled = !inFlight && phone.isNotBlank() && (amount.toLongOrNull() ?: 0L) > 0,
                modifier = Modifier.semantics { testTag = "wallet_transfer_submit" },
            ) {
                Text(stringResource(R.string.wallet_transfer_do))
            }
            last?.let {
                Text(
                    text = stringResource(
                        R.string.wallet_transfer_ok,
                        it.receiverGotMinor.toString(),
                        it.feeMinor.toString(),
                        it.balanceMinor.toString(),
                    ),
                    modifier = Modifier.semantics { testTag = "wallet_transfer_ok" },
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(items: List<WalletHistoryItemDto>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "wallet_history" },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.wallet_history_title), style = MaterialTheme.typography.labelMedium)
            if (items.isEmpty()) {
                Text(stringResource(R.string.wallet_history_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { testTag = "wallet_history_row" },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.reason, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = formatWhen(item.createdAtEpochSec),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val sign = if (item.direction == "DEBIT") "-" else "+"
                        Text(
                            text = "$sign${item.amountMinor}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** bps -> a plain percent label (250 bps -> "2.5%", 0 -> "0%"). */
private fun percentLabel(bps: Int): String {
    val percent = bps / 100.0
    val raw = if (percent == percent.toLong().toDouble()) {
        percent.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", percent)
    }
    return "$raw%"
}

/** Epoch-sec -> "2026-11-28 12:34" UTC-ish label (locale-stable, no timezone surprises). */
private fun formatWhen(epochSec: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(epochSec * 1000))
