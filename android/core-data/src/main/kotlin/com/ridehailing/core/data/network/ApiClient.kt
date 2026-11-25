package com.ridehailing.core.data.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Qualifier for the shared API HttpClient (network layer singleton). */
@javax.inject.Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiClient

/**
 * Shared JSON: forward-compatible parsing (unknown keys ignored, D46) - the backend may
 * add fields ahead of the app release without breaking older clients.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
}

/**
 * Ktor HttpClient factory (P2-4). Engine injectable so JVM tests pass a MockEngine and
 * the device build uses CIO. Cleartext http is fine for the self-hosted dev/CI stack
 * (the apps already set usesCleartextTraffic for the tile server + test viewer).
 */
object ApiClientFactory {
    fun create(engine: HttpClientEngine = CIO.create()): HttpClient =
        HttpClient(engine) {
            // Error mapping lives in postTyped (typed ApiError from the Error schema);
            // expectSuccess would throw before that mapping could run.
            expectSuccess = false
            install(ContentNegotiation) { json(ApiJson) }
        }
}

/** Non-2xx -> typed [ApiError] (body Error{code,message} when parseable, else status text). */
suspend fun apiErrorFrom(response: HttpResponse): ApiError {
    val status: HttpStatusCode = response.status
    return try {
        val dto = response.body<ApiErrorDto>()
        ApiError(code = dto.code, message = dto.message, httpStatus = status.value)
    } catch (_: Exception) {
        ApiError(
            code = "http_${status.value}",
            message = status.description.ifBlank { "HTTP ${status.value}" },
            httpStatus = status.value,
        )
    }
}

/** Post + typed error mapping; the single request path every auth call goes through. */
suspend inline fun <reified T> HttpClient.postTyped(
    path: String,
    body: Any,
): T = post(path) {
    contentType(ContentType.Application.Json)
    setBody(body)
}.let { response ->
    if (response.status.isSuccess()) response.body<T>()
    else throw ApiRequestException(apiErrorFrom(response))
}

/** Get + typed error mapping (P4-3 config bundle; P4-4 rides endpoints). */
suspend inline fun <reified T> HttpClient.getTyped(
    path: String,
): T = get(path).let { response ->
    if (response.status.isSuccess()) response.body<T>()
    else throw ApiRequestException(apiErrorFrom(response))
}

class ApiRequestException(val error: ApiError) : RuntimeException("${error.code}: ${error.message}")
