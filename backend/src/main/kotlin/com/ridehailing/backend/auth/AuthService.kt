package com.ridehailing.backend.auth

/**
 * Authentication orchestrator (P1-2). Owns registration, password login, OTP flows
 * (login or auto-register), token issuance/rotation and logout. All mutations are
 * recorded through [EventChainRecorder] so logins/registrations land in the
 * append-only, hash-chained event store (B-08 full history).
 */
class AuthService(
    private val users: UserStore,
    private val passwords: PasswordService,
    private val tokens: JwtService,
    private val otp: OtpService,
    private val loginMethods: LoginMethodsConfig,
    private val eventRecorder: EventChainRecorder,
    private val clock: () -> java.time.Instant = java.time.Instant::now,
) {
    fun interface EventChainRecorder {
        fun append(type: String, payload: Map<String, String?>)
    }

    sealed class RegisterResult {
        data class Success(val user: User, val pair: JwtService.TokenPair) : RegisterResult()
        data class Invalid(val field: String, val code: String) : RegisterResult()
        data object Duplicate : RegisterResult()
    }

    fun register(
        displayNameRaw: String,
        passwordRaw: String,
        phoneRaw: String?,
        emailRaw: String?,
        localeRaw: String?,
    ): RegisterResult {
        // D49 contract: documented as raw length 2..64 with at least one
        // non-(ASCII-whitespace) character (openapi RegisterRequest.displayName
        // pattern '[^ \t\n\u000B\f\r ]', unanchored search); stored ASCII-trimmed.
        // ASCII-only whitespace + explicit \u000B keeps Kotlin/ECMA/python regex
        // semantics identical (Java interprets \v as the vertical-whitespace CLASS),
        // which ContractAlignmentTest asserts so schema and code cannot drift.
        val displayName = displayNameRaw.trim(' ', '\t', '\n', '\u000B', '\u000C', '\r')
        if (displayNameRaw.length !in 2..64 || displayName.isEmpty()) {
            return RegisterResult.Invalid("displayName", "invalid_display_name")
        }
        val policy = passwords.policyViolation(passwordRaw)
        if (policy != null) return RegisterResult.Invalid("password", policy)
        val phone = phoneRaw?.let { Identifiers.normalizePhone(it) }
            ?: if (phoneRaw != null) return RegisterResult.Invalid("phone", "invalid_phone") else null
        val email = emailRaw?.let { Identifiers.normalizeEmail(it) }
            ?: if (emailRaw != null) return RegisterResult.Invalid("email", "invalid_email") else null
        if (phone == null && email == null) return RegisterResult.Invalid("identifier", "identifier_required")
        if (phone != null && users.findByPhone(phone) != null) return RegisterResult.Duplicate
        if (email != null && users.findByEmail(email) != null) return RegisterResult.Duplicate
        val now = clock()
        val user = User(
            id = Ids.newId(),
            phone = phone,
            email = email,
            passwordHash = passwords.hash(passwordRaw),
            displayName = displayName,
            role = Role.RIDER,
            status = UserStatus.ACTIVE,
            locale = localeRaw?.takeIf { it in setOf("en", "fr", "ar") } ?: "en",
            createdAtEpochSec = now.epochSecond,
        )
        users.insert(user)
        eventRecorder.append(
            "auth.register",
            mapOf("userId" to user.id, "identifier" to (phone ?: email), "channel" to (if (phone != null) "phone" else "email")),
        )
        return RegisterResult.Success(user, tokens.issueSession(user.id, user.role.name, now))
    }

    sealed class PasswordLoginResult {
        data class Success(val user: User, val pair: JwtService.TokenPair) : PasswordLoginResult()
        data object InvalidCredentials : PasswordLoginResult()
        data object Disabled : PasswordLoginResult()
        data object Banned : PasswordLoginResult()
    }

    /**
     * Password login. Uniform "invalid credentials" response for unknown identifier
     * and wrong password (no user-enumeration oracle); a dummy argon2 verify runs for
     * unknown identifiers to equalize timing (B-04).
     */
    fun loginWithPassword(identifier: String, password: String): PasswordLoginResult {
        if (!loginMethods.passwordLoginEnabled) return PasswordLoginResult.Disabled
        val user = identifier.toUser()
        val hash = user?.passwordHash
        val ok = if (user != null && hash != null) passwords.verify(password, hash) else {
            // Equalize timing: burn one argon2id verification against a throwaway hash.
            passwords.verify(password, DUMMY_HASH)
            false
        }
        if (!ok) return PasswordLoginResult.InvalidCredentials
        val u = user!!
        when (u.status) {
            UserStatus.BANNED_TEMP, UserStatus.BANNED_PERM -> return PasswordLoginResult.Banned
            UserStatus.DELETED -> return PasswordLoginResult.InvalidCredentials
            UserStatus.ACTIVE -> Unit
        }
        eventRecorder.append("auth.login.password", mapOf("userId" to u.id))
        return PasswordLoginResult.Success(u, tokens.issueSession(u.id, u.role.name, clock()))
    }

    sealed class OtpIssueResult {
        data class Issued(val requestId: String, val expiresInSeconds: Long, val resendAfterSeconds: Long) : OtpIssueResult()
        data class Refused(val code: String) : OtpIssueResult()
    }

    suspend fun requestOtp(channel: OtpChannel, target: String, locale: String): OtpIssueResult =
        otp.issue(channel, target, locale).fold(
            onLeft = { OtpIssueResult.Issued(it.requestId, it.expiresInSeconds, it.resendAfterSeconds) },
            onRight = { OtpIssueResult.Refused(it) },
        )

    sealed class OtpVerifyResult {
        data class Success(val user: User, val pair: JwtService.TokenPair, val created: Boolean) : OtpVerifyResult()
        data class Failed(val code: String) : OtpVerifyResult()
    }

    /**
     * Verify an OTP. Known identifier -> login. Unknown identifier -> auto-register a
     * RIDER when [LoginMethodsConfig.otpAutoRegister] is enabled (standard ride-hailing
     * behaviour; admin-toggleable). The verified identifier becomes the account key.
     */
    suspend fun verifyOtp(requestId: String, code: String, displayNameRaw: String?, locale: String): OtpVerifyResult {
        val match = when (val v = otp.verify(requestId, code)) {
            is OtpService.VerifyOutcome.Match -> v
            is OtpService.VerifyOutcome.Expired -> return OtpVerifyResult.Failed("otp_expired")
            is OtpService.VerifyOutcome.TooManyAttempts -> return OtpVerifyResult.Failed("otp_too_many_attempts")
            is OtpService.VerifyOutcome.UnknownRequest -> return OtpVerifyResult.Failed("otp_invalid")
        }
        val phone = if (match.channel == OtpChannel.SMS) Identifiers.normalizePhone(match.target) else null
        val email = if (match.channel == OtpChannel.EMAIL) Identifiers.normalizeEmail(match.target) else null
        if (phone == null && email == null) return OtpVerifyResult.Failed("otp_invalid")
        val existing = phone?.let { users.findByPhone(it) } ?: email?.let { users.findByEmail(it) }
        val now = clock()
        if (existing != null) {
            eventRecorder.append("auth.login.otp", mapOf("userId" to existing.id, "channel" to match.channel.name))
            return OtpVerifyResult.Success(existing, tokens.issueSession(existing.id, existing.role.name, now), created = false)
        }
        if (!loginMethods.otpAutoRegister) return OtpVerifyResult.Failed("registration_disabled")
        val displayName = displayNameRaw?.trim()?.takeIf { it.length in 2..64 } ?: "Rider"
        val user = User(
            id = Ids.newId(),
            phone = phone,
            email = email,
            passwordHash = null,
            displayName = displayName,
            role = Role.RIDER,
            status = UserStatus.ACTIVE,
            locale = locale.takeIf { it in setOf("en", "fr", "ar") } ?: "en",
            createdAtEpochSec = now.epochSecond,
        )
        users.insert(user)
        eventRecorder.append("auth.register.otp", mapOf("userId" to user.id, "channel" to match.channel.name))
        return OtpVerifyResult.Success(user, tokens.issueSession(user.id, user.role.name, now), created = true)
    }

    sealed class RefreshResult2 {
        data class Success(val pair: JwtService.TokenPair) : RefreshResult2()
        data class Failed(val code: String) : RefreshResult2()
    }

    fun refresh(refreshToken: String): RefreshResult2 = when (val r = tokens.refresh(refreshToken)) {
        is JwtService.RefreshResult.Rotated -> RefreshResult2.Success(r.pair)
        is JwtService.RefreshResult.ReuseDetected -> RefreshResult2.Failed("refresh_reuse_detected")
        is JwtService.RefreshResult.Invalid -> RefreshResult2.Failed("refresh_invalid")
    }

    fun logout(refreshToken: String): Boolean = tokens.revokeSession(refreshToken)

    fun currentUser(accessToken: String): User? {
        val userId = try {
            tokens.buildVerifier().verify(accessToken).subject
        } catch (_: Exception) {
            return null
        }
        return users.findById(userId)?.takeIf { it.status != UserStatus.DELETED && it.status != UserStatus.BANNED_PERM }
    }

    private fun String.toUser(): User? {
        val phone = Identifiers.normalizePhone(this)?.let { users.findByPhone(it) }
        if (phone != null) return phone
        val email = Identifiers.normalizeEmail(this)?.let { users.findByEmail(it) }
        return email
    }

    companion object {
        /**
         * Dummy argon2id hash (computed once, lazily) used ONLY to equalize login
         * timing for unknown identifiers: the full cost of one verification runs on
         * every miss, so response time cannot distinguish unknown-user from
         * wrong-password (B-04, no user-enumeration oracle).
         */
        private val DUMMY_HASH: String by lazy {
            PasswordService().hash("timing-equalizer-dummy-credential")
        }
    }
}
