package com.ridehailing.core.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridehailing.core.data.network.ApiError
import com.ridehailing.core.data.network.ApiRequestException
import com.ridehailing.core.data.network.AuthRepository
import com.ridehailing.core.data.rides.DriverRepository
import com.ridehailing.core.data.wallet.EarningsAggregator
import com.ridehailing.core.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Driver earnings UI state (P7-4). Verbatim errors, no invented money. */
data class DriverEarningsUiState(
    val signedIn: Boolean = false,
    val loading: Boolean = false,
    val summary: EarningsAggregator.Summary? = null,
    val error: ApiError? = null,
)

/**
 * P7-4 driver earnings state: pulls the driver's assigned rides + the driver's own
 * ledger history and aggregates client-side ([EarningsAggregator]); the backend adds
 * no dedicated earnings endpoint in v1. Errors surface VERBATIM.
 */
@HiltViewModel
class DriverEarningsViewModel @Inject constructor(
    private val driver: DriverRepository,
    private val wallet: WalletRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DriverEarningsUiState())
    val state: StateFlow<DriverEarningsUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val signedIn = auth.sessionFlow.first().accessToken?.isNotEmpty() == true
            _state.value = _state.value.copy(signedIn = signedIn, loading = signedIn, error = null)
            if (!signedIn) return@launch
            try {
                // myRides covers the summary window (50 newest); history max page (200)
                // gives the capture-credit join room. Larger windows are a Phase-8 paged
                // UI - the aggregator invents nothing beyond what these pages returned.
                val rides = driver.myRides(limit = 50).getOrDefault(emptyList())
                val history = wallet.transactions(limit = 200, offset = 0).items
                val summary = EarningsAggregator.summarize(
                    rides = rides,
                    history = history,
                    nowEpochSec = System.currentTimeMillis() / 1000,
                )
                _state.value = _state.value.copy(loading = false, summary = summary)
            } catch (e: ApiRequestException) {
                _state.value = _state.value.copy(loading = false, error = e.error)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = ApiError(code = "earnings_unavailable", message = e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }
}
