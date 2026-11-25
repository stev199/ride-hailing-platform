package com.ridehailing.core.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridehailing.core.data.network.ApiRequestException
import com.ridehailing.core.data.network.AuthRepository
import com.ridehailing.core.data.network.DriverRideDto
import com.ridehailing.core.data.rides.DriverAction
import com.ridehailing.core.data.rides.DriverRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DriverUiState(
    val online: Boolean = false,
    val dutySyncing: Boolean = false,
    /** VERBATIM server error on the duty toggle (driver_role_required, invalid_coordinates, ...). */
    val dutyError: String? = null,
    val offers: List<DriverRideDto> = emptyList(),
    val offersError: String? = null,
    /** The driver's live trip (latest non-terminal assigned ride) - null when idle. */
    val activeRide: DriverRideDto? = null,
    val acting: Boolean = false,
    val actionError: String? = null,
)

/**
 * P5 driver flow state machine: duty toggle (server-backed truth) -> dispatch board
 * polling (closest first) -> single-winner accept -> trip actions
 * (ARRIVED -> IN_PROGRESS -> COMPLETED). HONEST SCOPE v1: the duty position is the map
 * center constant (MapConfig.DEFAULT_CENTER) - real GPS fixes land with the location
 * permission work; the backend stores whatever position the app sends.
 */
@HiltViewModel
class DriverViewModel @Inject constructor(
    private val driver: DriverRepository,
    auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DriverUiState())
    val state: StateFlow<DriverUiState> = _state.asStateFlow()

    /** Signed-in visibility for the duty card (drivers must log in before going online). */
    val signedIn: StateFlow<Boolean> = auth.sessionFlow
        .map { it?.accessToken != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var pollJob: Job? = null

    fun toggleDuty(online: Boolean) {
        if (_state.value.dutySyncing) return
        _state.update { it.copy(dutySyncing = true, dutyError = null) }
        viewModelScope.launch {
            val result = driver.setDuty(online, MapConfig.DEFAULT_CENTER_LAT, MapConfig.DEFAULT_CENTER_LNG)
            result
                .onSuccess { duty ->
                    _state.update { it.copy(dutySyncing = false, online = duty.online, dutyError = null) }
                    if (duty.online) startPolling() else stopPolling()
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(dutySyncing = false, online = false, dutyError = t.message ?: "duty_failed")
                    }
                }
        }
    }

    fun accept(rideId: String) {
        if (_state.value.acting) return
        _state.update { it.copy(acting = true, actionError = null) }
        viewModelScope.launch {
            driver.accept(rideId)
                .onSuccess { ride ->
                    _state.update { it.copy(acting = false, activeRide = ride, offers = emptyList()) }
                }
                .onFailure { t ->
                    _state.update { it.copy(acting = false, actionError = t.message ?: "accept_failed") }
                }
        }
    }

    fun tripAction(action: DriverAction) {
        val ride = _state.value.activeRide ?: return
        if (_state.value.acting) return
        _state.update { it.copy(acting = true, actionError = null) }
        viewModelScope.launch {
            driver.tripAction(ride.id, action)
                .onSuccess { updated ->
                    _state.update { it.copy(acting = false, activeRide = updated) }
                }
                .onFailure { t ->
                    _state.update { it.copy(acting = false, actionError = t.message ?: "action_failed") }
                }
        }
    }

    fun clearDutyError() = _state.update { it.copy(dutyError = null) }
    fun clearActionError() = _state.update { it.copy(actionError = null) }
    fun clearOffersError() = _state.update { it.copy(offersError = null) }

    /** Board + active trip poll while on duty (cheap GET pair every 5s). */
    private fun startPolling() {
        stopPolling()
        pollJob = viewModelScope.launch {
            while (isActive && _state.value.online) {
                refreshOnce()
                delay(5_000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun refreshOnce() {
        driver.availableRides()
            .onSuccess { offers -> _state.update { it.copy(offers = offers, offersError = null) } }
            .onFailure { t ->
                // A single failed poll must NOT drop the duty state - show it, retry next tick.
                _state.update { it.copy(offersError = (t as? ApiRequestException)?.error?.code ?: t.message) }
            }
        driver.myRides()
            .onSuccess { mine ->
                val active = mine.firstOrNull {
                    it.status == "ACCEPTED" || it.status == "ARRIVED" || it.status == "IN_PROGRESS"
                }
                _state.update { it.copy(activeRide = active) }
            }
    }
}
