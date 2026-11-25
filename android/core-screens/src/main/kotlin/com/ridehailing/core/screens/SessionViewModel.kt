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
        if (identifier.isBlank() || password.isEmpty()) return
        _loginError.value = null
        _signingIn.value = true
        viewModelScope.launch {
            try {
                _signedInUser.value = auth.login(identifier, password)
            } catch (e: ApiRequestException) {
                _loginError.value = e
            } finally {
                _signingIn.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }
}
