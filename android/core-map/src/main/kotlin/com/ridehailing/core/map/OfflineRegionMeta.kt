package com.ridehailing.core.map

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Metadata envelope stored INSIDE an offline region (MapLibre persists opaque bytes).
 * Pure Kotlin: JVM-testable, forward-compatible (D46 ignoreUnknownKeys).
 */
@Serializable
data class OfflineRegionMeta(
    val name: String,
    val createdAtEpochSec: Long,
    val app: String = "ride-hailing",
)

object OfflineRegionMetaCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun encode(meta: OfflineRegionMeta): ByteArray = json.encodeToString(OfflineRegionMeta.serializer(), meta).toByteArray(Charsets.UTF_8)

    /** Decoding failures degrade to an "unknown region" name - never crash the list. */
    fun decode(bytes: ByteArray): OfflineRegionMeta? = try {
        json.decodeFromString(OfflineRegionMeta.serializer(), bytes.decodeToString())
    } catch (_: Exception) {
        null
    }
}

/**
 * Download progress math (pure): percent clamped to 0..100, 0 when nothing is required
 * yet, and NEVER > 100 even if completedResourceCount over-reports on retries.
 */
object OfflineProgressMath {
    fun percent(completedResourceCount: Long, requiredResourceCount: Long): Int {
        if (requiredResourceCount <= 0) return 0
        val pct = (completedResourceCount * 100 / requiredResourceCount).toInt()
        return pct.coerceIn(0, 100)
    }
}
