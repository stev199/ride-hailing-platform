package com.ridehailing.core.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridehailing.core.data.network.ApiError
import com.ridehailing.core.data.network.ApiRequestException
import com.ridehailing.core.data.network.AuthRepository
import com.ridehailing.core.data.network.DeviceWalletConfig
import com.ridehailing.core.data.network.WalletBalancesDto
import com.ridehailing.core.data.network.WalletHistoryItemDto
import com.ridehailing.core.data.network.WalletTransferResultDto
import com.ridehailing.core.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One wallet UI state (P7-3). Errors are VERBATIM server errors - no UI wording. */
data class WalletUiState(
    val signedIn: Boolean = false,
    val loading: Boolean = false,
    val balances: WalletBalancesDto? = null,
    val config: DeviceWalletConfig? = null,
    val history: List<WalletHistoryItemDto> = emptyList(),
    val transferInFlight: Boolean = false,
    /** Last successful transfer - rendered once as a confirmation line. */
    val lastTransfer: WalletTransferResultDto? = null,
    /** Verbatim error (code + message) from the latest failed operation. */
    val error: ApiError? = null,
)

/**
 * P7-3 rider wallet state: balances + held, ledger history (first page), points
 * transfer by phone. Signed-out shows the prompt (no requests leave the app);
 * auth/network/server failures surface verbatim, exactly like the auth surface.
 */
@HiltViewModel
class WalletViewModel @Inject constructor(
    private val wallet: WalletRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state

    init {
        refresh()
    }

    /** Re-reads the session then pulls balances + config + history (online-only v1). */
    fun refresh() {
        viewModelScope.launch {
            val signedIn = auth.sessionFlow.first().accessToken?.isNotEmpty() == true
            _state.value = _state.value.copy(signedIn = signedIn, loading = signedIn, error = null)
            if (!signedIn) return@launch
            try {
                val balances = wallet.balances()
                val config = wallet.walletConfig()
                val history = try {
                    wallet.transactions(limit = 20, offset = 0).items
                } catch (_: Exception) {
                    // History is secondary - a failure there must not blank the balance card.
                    _state.value.history
                }
                _state.value = _state.value.copy(
                    loading = false,
                    balances = balances,
                    config = config,
                    history = history,
                )
            } catch (e: ApiRequestException) {
                _state.value = _state.value.copy(loading = false, error = e.error)
            } catch (e: IllegalStateException) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = ApiError(code = e.message ?: "auth_required", message = e.message ?: ""),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = ApiError(code = "wallet_unavailable", message = e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    /** POST /v1/wallet/transfer; on success reloads balances + history, keeps the fee line. */
    fun transfer(toPhone: String, amountMinor: Long, note: String?) {
        if (toPhone.isBlank() || amountMinor <= 0) return
        _state.value = _state.value.copy(transferInFlight = true, error = null)
        viewModelScope.launch {
            try {
                val result = wallet.transfer(toPhone, amountMinor, note?.trim()?.takeIf { it.isNotEmpty() })
                _state.value = _state.value.copy(
                    transferInFlight = false,
                    lastTransfer = result,
                )
                refresh()
            } catch (e: ApiRequestException) {
                _state.value = _state.value.copy(transferInFlight = false, error = e.error)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    transferInFlight = false,
                    error = ApiError(code = "wallet_unavailable", message = e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    /** Clear the one-shot confirmation line after the UI has shown it. */
    fun consumeTransferNote() {
        _state.value = _state.value.copy(lastTransfer = null)
    }
}
