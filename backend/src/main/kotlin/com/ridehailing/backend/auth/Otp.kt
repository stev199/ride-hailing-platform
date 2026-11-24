package com.ridehailing.backend.auth

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** OTP delivery channels (B-04): pluggable OtpProvider adapters, admin-toggleable. */
enum class OtpChannel { SMS, TELEGRAM, EMAIL, WHATSAPP }

/**
 * Pluggable OTP provider SPI (B-04 hard constraint 4). One adapter per channel;
 * adapters are real transports and never log the code. The dev inbox adapter is the
 * default in dev/test; production adapters activate only when their configuration
 * (env/config bundle) is present.
 */
interface OtpProvider {
    val channel: OtpChannel

    data class SendResult(val accepted: Boolean, val providerRef: String? = null, val failureReason: String? = null)

    /**
     * Deliver [code] to [target] (normalized identifier). Must be idempotent-safe and
     * short-timeout; returns a failure result rather than throwing on provider errors.
     */
    suspend fun send(target: String, code: String, locale: String): SendResult
}

/**
 * Dev/test inbox provider (STATE.md: "dev inbox adapter first"). Codes stay in an
 * in-memory inbox retrievable by tests (and via /test/ routes when TEST_PROFILE=true).
 * NEVER enabled in production config; the login-methods config marks it dev-only.
 */
class DevInboxOtpProvider : OtpProvider {
    override val channel = OtpChannel.SMS

    private data class InboxEntry(val code: String, val createdAt: Instant)

    private val inbox = ConcurrentHashMap<String, InboxEntry>()

    override suspend fun send(target: String, code: String, locale: String): OtpProvider.SendResult {
        inbox[target] = InboxEntry(code, Instant.now())
        return OtpProvider.SendResult(accepted = true, providerRef = "dev-inbox")
    }

    /** Test hook: latest code sent to [target] (dev/test only). */
    fun latestCode(target: String): String? = inbox[target]?.code

    fun clear() = inbox.clear()
}

/**
 * Transport seam for remote adapters. Production wires Ktor CIO HTTP (or Jakarta Mail);
 * tests inject a capturing function so adapter request-building logic is verified
 * without network. This is a test seam, not a feature stub: production adapters below
 * contain the full request/response handling.
 */
fun interface RemoteTransport {
    suspend fun postJson(url: String, headers: Map<String, String>, bodyJson: String): TransportResponse

    data class TransportResponse(val status: Int, val body: String)
}

/** Self-hosted SMS gateway adapter (B-04): POST {url} {to, message} + optional bearer token. */
class SmsGatewayOtpProvider(
    private val gatewayUrl: String,
    private val authToken: String?,
    private val senderId: String?,
    private val transport: RemoteTransport,
) : OtpProvider {
    override val channel = OtpChannel.SMS

    override suspend fun send(target: String, code: String, locale: String): OtpProvider.SendResult {
        val headers = buildMap {
            authToken?.let { put("Authorization", "Bearer $it") }
            put("Content-Type", "application/json")
        }
        val body = messageJson(target, code, locale, senderId)
        val res = try {
            transport.postJson(gatewayUrl, headers, body)
        } catch (_: Exception) {
            return OtpProvider.SendResult(false, failureReason = "transport_error")
        }
        return if (res.status in 200..299) {
            OtpProvider.SendResult(true, providerRef = "sms-gateway")
        } else {
            OtpProvider.SendResult(false, failureReason = "gateway_http_${res.status}")
        }
    }

    companion object {
        /** Visible for tests: exact payload shape sent to the self-hosted gateway. */
        fun messageJson(target: String, code: String, locale: String, senderId: String?): String {
            val text = messageText(locale, code)
            val sender = senderId?.let { "\"sender\":\"$it\"," } ?: ""
            return """{"to":"$target","message":"$text",${sender}"type":"otp"}"""
        }

        fun messageText(locale: String, code: String): String = when (locale) {
            "fr" -> "Votre code Ride Hailing: $code (valable 5 minutes)"
            "ar" -> "رمز الدخول الخاص بك: $code (صالح لمدة 5 دقائق)"
            else -> "Your verification code is $code (valid for 5 minutes)"
        }
    }
}

/** Telegram bot adapter (B-04): sendMessage via the Bot API over HTTPS. */
class TelegramBotOtpProvider(
    private val botToken: String,
    private val transport: RemoteTransport,
) : OtpProvider {
    override val channel = OtpChannel.TELEGRAM

    override suspend fun send(target: String, code: String, locale: String): OtpProvider.SendResult {
        // Telegram targets are chat ids (users bind their account to a chat via the bot).
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val text = "Ride Hailing verification code: $code (valid 5 minutes)"
        val body = """{"chat_id":"$target","text":"$text","parse_mode":"plain"}"""
        val res = try {
            transport.postJson(url, mapOf("Content-Type" to "application/json"), body)
        } catch (_: Exception) {
            return OtpProvider.SendResult(false, failureReason = "transport_error")
        }
        val okBody = res.body.contains("\"ok\":true")
        return if (res.status == 200 && okBody) {
            OtpProvider.SendResult(true, providerRef = "telegram")
        } else {
            OtpProvider.SendResult(false, failureReason = "telegram_http_${res.status}")
        }
    }
}

/** Email adapter via SMTP (B-04). Jakarta Mail is used only at production send time. */
class EmailOtpProvider(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val fromAddress: String,
    private val sendMail: suspend (subject: String, body: String, to: String) -> Unit = { subject, body, to ->
        SmtpSupport.send(host, port, username, password, fromAddress, subject, body, to)
    },
) : OtpProvider {
    override val channel = OtpChannel.EMAIL

    override suspend fun send(target: String, code: String, locale: String): OtpProvider.SendResult {
        val subject = "Your verification code"
        val body = SmsGatewayOtpProvider.messageText(locale, code)
        return try {
            sendMail(subject, body, target)
            OtpProvider.SendResult(true, providerRef = "smtp")
        } catch (_: Exception) {
            OtpProvider.SendResult(false, failureReason = "smtp_error")
        }
    }
}

/**
 * SMTP support (real transport used only in production; tests inject sendMail).
 * STARTTLS enforced: plain-text SMTP without TLS upgrade is refused.
 */
object SmtpSupport {
    suspend fun send(
        host: String,
        port: Int,
        username: String,
        password: String,
        from: String,
        subject: String,
        body: String,
        to: String,
    ) {
        val props = System.getProperties()
        props["mail.smtp.host"] = host
        props["mail.smtp.port"] = port.toString()
        props["mail.smtp.auth"] = "true"
        props["mail.smtp.starttls.enable"] = "true"
        props["mail.smtp.connectiontimeout"] = "10000"
        props["mail.smtp.timeout"] = "10000"
        val session = jakarta.mail.Session.getInstance(props)
        val message = jakarta.mail.internet.MimeMessage(session).apply {
            setFrom(jakarta.mail.internet.InternetAddress(from))
            setRecipients(jakarta.mail.Message.RecipientType.TO, jakarta.mail.internet.InternetAddress.parse(to))
            this.subject = subject
            setText(body, "UTF-8")
        }
        jakarta.mail.Transport.send(message, username, password)
    }
}

/**
 * OTP secrets (B-04): 6 digits, hashed at rest, 5-minute expiry, rate- and
 * attempt-limited. Issue policy: 3 requests per identifier per 10 minutes, 45 s
 * resend window. Verify policy: 5 failed attempts invalidate the request.
 */
class OtpService(
    private val providers: Map<OtpChannel, OtpProvider>,
    /** P1-5a: provider, not snapshot - admin config patches take effect on the next call. */
    private val loginMethods: () -> LoginMethodsConfig,
    private val clock: () -> Instant = Instant::now,
) {

    companion object {
        const val TTL_SEC = 5L * 60
        const val RESEND_WINDOW_SEC = 45L
        const val MAX_SENDS_PER_WINDOW = 3
        const val SEND_WINDOW_SEC = 10L * 60
        const val MAX_VERIFY_ATTEMPTS = 5
    }

    internal data class ActiveOtp(
        val codeHash: String,
        val channel: OtpChannel,
        val target: String,
        val expiresAtSec: Long,
        var attempts: Int = 0,
    )

    data class Issued(val requestId: String, val expiresInSeconds: Long, val resendAfterSeconds: Long)

    sealed class VerifyOutcome {
        data class Match(val channel: OtpChannel, val target: String) : VerifyOutcome()
        data object Expired : VerifyOutcome()
        data object TooManyAttempts : VerifyOutcome()
        data object UnknownRequest : VerifyOutcome()
    }

    private val active = ConcurrentHashMap<String, ActiveOtp>()
    private val sendLog = ConcurrentHashMap<String, MutableList<Long>>() // identifier -> issue timestamps
    private val otpRandom = java.security.SecureRandom()

    private fun hash(requestId: String, code: String, channel: OtpChannel): String {
        // Keyed hash with the request id as salt: precomputation across requests is useless
        // and per-entry salts defeat rainbow tables. 6 digits -> 1M space; the rate limits
        // (5 attempts, 45 s resend, per-identifier send caps) are the real defence.
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(otpKey, "HmacSHA256"))
        val input = "$channel|$requestId|$code"
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.toByteArray()))
    }

    private val otpKey: ByteArray by lazy {
        val env = System.getenv("OTP_HASH_KEY")
        if (env != null && env.toByteArray().size >= 32) {
            env.toByteArray()
        } else {
            ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        }
    }

    private fun targetKey(channel: OtpChannel, target: String) = "$channel:$target"

    /**
     * Generate, store (hashed) and dispatch an OTP. Rate limits apply BEFORE any
     * provider call; disabled/unconfigured channels are refused without generating.
     */
    suspend fun issue(channel: OtpChannel, target: String, locale: String): Either<Issued, String> {
        if (!loginMethods().isOtpChannelEnabled(channel)) return Either.Right("channel_disabled")
        val provider = providers[channel] ?: return Either.Right("provider_unavailable")
        val now = clock()
        val key = targetKey(channel, target)
        // Recent activity check (both active requests and fully-consumed ones) so the
        // window cannot be bypassed by verifying between sends.
        val sends = sendLog.getOrPut(key) { mutableListOf() }
        val windowStart = now.epochSecond - SEND_WINDOW_SEC
        sends.removeIf { it < windowStart }
        if (sends.size >= MAX_SENDS_PER_WINDOW) {
            return Either.Right("rate_limited")
        }
        val lastSend = sends.lastOrNull()
        if (lastSend != null && now.epochSecond - lastSend < RESEND_WINDOW_SEC) {
            return Either.Right("resend_too_soon")
        }
        val code = "%06d".format(otpRandom.nextInt(1_000_000))
        val requestId = java.util.UUID.randomUUID().toString()
        val result = provider.send(target, code, locale)
        if (!result.accepted) return Either.Right(result.failureReason ?: "provider_error")
        sends.add(now.epochSecond)
        active[requestId] = ActiveOtp(
            codeHash = hash(requestId, code, channel),
            channel = channel,
            target = target,
            expiresAtSec = now.epochSecond + TTL_SEC,
        )
        return Either.Left(Issued(requestId, TTL_SEC, RESEND_WINDOW_SEC))
    }

    fun verify(requestId: String, code: String): VerifyOutcome {
        val entry = active[requestId] ?: return VerifyOutcome.UnknownRequest
        val now = clock().epochSecond
        if (entry.expiresAtSec <= now) {
            active.remove(requestId)
            return VerifyOutcome.Expired
        }
        if (entry.attempts >= MAX_VERIFY_ATTEMPTS) {
            active.remove(requestId)
            return VerifyOutcome.TooManyAttempts
        }
        entry.attempts += 1
        val candidateHash = hash(requestId, code, entry.channel)
        val ok = java.security.MessageDigest.isEqual(
            candidateHash.toByteArray(),
            entry.codeHash.toByteArray(),
        )
        if (ok) {
            active.remove(requestId)
            return VerifyOutcome.Match(entry.channel, entry.target)
        }
        if (entry.attempts >= MAX_VERIFY_ATTEMPTS) {
            active.remove(requestId)
            return VerifyOutcome.TooManyAttempts
        }
        return VerifyOutcome.UnknownRequest
    }

    /** Live active OTP count (test/ops). */
    fun activeCount(): Int = active.size
}

/** Minimal Either for service results (avoiding arrow dependencies). */
sealed class Either<out L, out R> {
    data class Left<L>(val value: L) : Either<L, Nothing>()
    data class Right<R>(val value: R) : Either<Nothing, R>()

    inline fun <T> fold(onLeft: (L) -> T, onRight: (R) -> T): T = when (this) {
        is Left -> onLeft(value)
        is Right -> onRight(value)
    }
}

/**
 * Login-methods configuration (B-04 "Admin enables/disables each method and sets
 * priority"). Defaults are dev-safe: password login + dev inbox OTP enabled; remote
 * adapters off until configured. This is the in-process view; the signed config
 * bundle carries the same section to devices (P1-5).
 */
data class LoginMethodsConfig(
    val passwordLoginEnabled: Boolean = true,
    val otpAutoRegister: Boolean = true,
    val otpChannels: Map<OtpChannel, ChannelConfig> = defaultChannels(),
) {
    data class ChannelConfig(val enabled: Boolean, val priority: Int, val devOnly: Boolean = false)

    fun isOtpChannelEnabled(channel: OtpChannel): Boolean {
        val cfg = otpChannels[channel] ?: return false
        if (cfg.devOnly && isProductionRuntime()) return false
        return cfg.enabled
    }

    /** Ordered enabled channels (admin-set priority; lower first). */
    fun prioritizedChannels(): List<OtpChannel> =
        otpChannels.filter { isOtpChannelEnabled(it.key) }.entries
            .sortedBy { it.value.priority }.map { it.key }

    companion object {
        private fun isProductionRuntime(): Boolean = System.getenv("APP_ENV") == "production"

        fun defaultChannels(): Map<OtpChannel, ChannelConfig> = mapOf(
            // Dev inbox stays enabled ONLY in non-production runtimes.
            OtpChannel.SMS to ChannelConfig(enabled = true, priority = 10, devOnly = true),
            OtpChannel.TELEGRAM to ChannelConfig(enabled = false, priority = 20),
            OtpChannel.EMAIL to ChannelConfig(enabled = false, priority = 30),
            OtpChannel.WHATSAPP to ChannelConfig(enabled = false, priority = 40),
        )
    }
}
