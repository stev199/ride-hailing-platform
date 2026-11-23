package com.ridehailing.backend.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/**
 * Production RemoteTransport over Ktor CIO (P1-2 OTP adapters): short timeouts, JSON
 * bodies, caller-supplied headers (auth bearer for gateways). Used by
 * SmsGatewayOtpProvider and TelegramBotOtpProvider when their configuration is present.
 */
class HttpRemoteTransport : RemoteTransport {
    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
    }

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        bodyJson: String,
    ): RemoteTransport.TransportResponse {
        val res = client.post(url) {
            headers.forEach { (k, v) -> header(k, v) }
            val hasContentType = headers.keys.any { it.equals(HttpHeaders.ContentType, ignoreCase = true) }
            if (!hasContentType) header(HttpHeaders.ContentType, "application/json")
            setBody(bodyJson)
        }
        return RemoteTransport.TransportResponse(res.status.value, res.bodyAsText())
    }
}
