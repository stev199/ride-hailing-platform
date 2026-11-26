package com.ridehailing.core.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridehailing.core.data.network.ApiRequestException
import com.ridehailing.core.data.network.AuthRepository
import com.ridehailing.core.data.network.UserDto
import com.ridehailing.core.data.prefs.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Auth state for the Settings tab (P2-4): signed-out shows the login form, signed-in
 * shows the session card + logout. Server errors surface VERBATIM (code + message) -
 * the backend is the authority; the UI adds no wording of its own.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    val session: StateFlow<Session?> = auth.sessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _loginError = MutableStateFlow<ApiRequestException?>(null)
    val loginError: StateFlow<ApiRequestException?> = _loginError

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn

    private val _signedInUser = MutableStateFlow<UserDto?>(null)
    val signedInUser: StateFlow<UserDto?> = _signedInUser

    fun login(identifier: String, password: String) {
        // Round-15h: the emulator login click never produces ANY observable effect
        // (CI #195-#206) - each step must name itself in logcat so the e2e artifact
        // shows exactly how far the flow gets on device.
        android.util.Log.i("e2e-login", "login() entered idLen=${identifier.length} pwLen=${password.length}")
        if (identifier.isBlank() || password.isEmpty()) {
            android.util.Log.i("e2e-login", "login() guard return (empty fields)")
            return
        }
        _loginError.value = null
        _signingIn.value = true
        viewModelScope.launch {
            android.util.Log.i("e2e-login", "login() coroutine started")
            try {
                _signedInUser.value = auth.login(identifier, password)
                android.util.Log.i("e2e-login", "login() SUCCESS userId=${_signedInUser.value?.id}")
            } catch (e: ApiRequestException) {
                android.util.Log.i("e2e-login", "login() ApiError ${e.error.code}")
                _loginError.value = e
            } catch (e: Exception) {
                // Round-15c: ANY other failure (IOException on the emulator, serialization)
                // must surface as login_error - an uncaught exception here killed the whole
                // coroutine silently: neither session_info nor login_error ever appeared and
                // the e2e burned its 20s wait against a dead UI (CI #193/#194).
                android.util.Log.i("e2e-login", "login() non-Api exception ${e.javaClass.simpleName}: ${e.message}")
                _loginError.value = com.ridehailing.core.data.network.ApiRequestException(
                    com.ridehailing.core.data.network.ApiError(
                        code = "login_unavailable",
                        message = e.message ?: e.javaClass.simpleName,
                    )
                )
            } finally {
                _signingIn.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }
}
