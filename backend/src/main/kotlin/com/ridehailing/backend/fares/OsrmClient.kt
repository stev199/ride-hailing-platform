package com.ridehailing.backend.fares

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * P4-1 OSRM route client (self-hosted osrm-routed, spec §0 compose stack).
 * Returns null on ANY failure (unreachable, non-2xx, malformed body, timeout) -
 * the estimate endpoint degrades to the straight-line fallback with the
 * degraded flag instead of failing the rider's estimate (spec §pricing:
 * online estimates use OSRM distance/time; the rider must never see a 5xx
 * because a geo service is down).
 */
class OsrmClient(private val baseUrl: String) : OsrmRouteSource {
    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 5_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    data class Route(val distanceMeters: Double, val durationSeconds: Double)

    /** GET /route/v1/driving/{lon1},{lat1};{lon2},{lat2}?overview=false - null on any failure. */
    override fun route(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Route? {
        return try {
            val enc: (Double) -> String = { v ->
                // Coordinates with '.' are safe; use plain toString to avoid locale drift.
                // URL-encode defensively anyway (comma/semicolon must be literal per OSRM API).
                URLEncoder.encode(v.toString(), StandardCharsets.UTF_8)
                    .replace("%2C", ",").replace("%2E", ".")
            }
            val url = "${baseUrl.trimEnd('/')}/route/v1/driving/" +
                "${enc(lon1)},${enc(lat1)};${enc(lon2)},${enc(lat2)}?overview=false&alternatives=false&steps=false"
            val pair = kotlinx.coroutines.runBlocking {
                val r = client.get(url)
                r.status to r.bodyAsText()
            }
            if (!pair.first.isSuccess()) return null
            val root = json.parseToJsonElement(pair.second).jsonObject
            val code = root["code"]?.jsonPrimitive?.content
            if (code != "Ok") return null
            val route0 = root["routes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val distance = route0["distance"]?.jsonPrimitive?.doubleOrNull ?: return null
            val duration = route0["duration"]?.jsonPrimitive?.doubleOrNull ?: return null
            Route(distance, duration)
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        client.close()
    }
}
