package com.ridehailing.core.map

import android.content.Context
import org.maplibre.android.geometry.LatLngBounds
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Terminal states are [Success]/[Failure]; [Progress] is emitted while downloading. */
sealed class OfflineDownloadResult {
    data class Progress(
        val percent: Int,
        val completedResourceCount: Long,
        val requiredResourceCount: Long,
    ) : OfflineDownloadResult()

    data class Success(val regionId: Long) : OfflineDownloadResult()
    data class Failure(val reason: String) : OfflineDownloadResult()
}

data class OfflineRegionSummary(
    val id: Long,
    val name: String,
)

/**
 * MapLibre offline regions (P3-1, Phase 3 "offline maps"): download a fixed city
 * extract once on a reachable network, then render it in airplane mode (spec hard
 * rule: self-hosted tiles only - the offline store holds OUR tiles, no third-party
 * hosts are ever contacted). Callback-style MapLibre APIs are wrapped in coroutines;
 * the pure parts (metadata codec, progress math) are JVM-tested.
 */
@Singleton
class OfflineMapManager @Inject constructor(@ApplicationContext private val context: Context) {

    private fun manager(): OfflineManager = OfflineManager.getInstance(context)

    /**
     * Download [name] for the tile-pyramid region around the map center. Emits
     * [OfflineDownloadResult.Progress] until the region completes or fails; the flow
     * CLOSES after the terminal event. The region is left INACTIVE (complete).
     */
    fun download(
        name: String,
        config: MapConfig,
        minZoom: Double = 0.0,
        maxZoom: Double = 14.0,
        pixelRatio: Float = 1f,
    ): Flow<OfflineDownloadResult> = callbackFlow {
        val manager = manager()
        val definition = OfflineTilePyramidRegionDefinition(
            config.styleUrl,
            LatLngBounds.from(
                (config.centerLat + SPAN_DEG).coerceAtMost(85.0),
                (config.centerLng + SPAN_DEG).coerceAtMost(180.0),
                (config.centerLat - SPAN_DEG).coerceAtLeast(-85.0),
                (config.centerLng - SPAN_DEG).coerceAtLeast(-180.0),
            ),
            minZoom,
            maxZoom,
            pixelRatio,
        )
        val meta = OfflineRegionMeta(name = name, createdAtEpochSec = System.currentTimeMillis() / 1000)
        manager.createOfflineRegion(
            definition,
            OfflineRegionMetaCodec.encode(meta),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            if (status.isComplete) {
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                trySend(OfflineDownloadResult.Success(region.id))
                                close()
                            } else {
                                trySend(
                                    OfflineDownloadResult.Progress(
                                        percent = OfflineProgressMath.percent(
                                            status.completedResourceCount,
                                            status.requiredResourceCount,
                                        ),
                                        completedResourceCount = status.completedResourceCount,
                                        requiredResourceCount = status.requiredResourceCount,
                                    ),
                                )
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            trySend(OfflineDownloadResult.Failure(error.reason + ": " + error.message))
                            close()
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            trySend(OfflineDownloadResult.Failure("tile_count_limit_$limit"))
                            close()
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    trySend(OfflineDownloadResult.Failure(error ?: "create_failed"))
                    close()
                }
            },
        )
        awaitClose { }
    }

    suspend fun listRegions(): List<OfflineRegionSummary> {
        val regions = listRegionsRaw()
        return regions.map { region ->
            val name = OfflineRegionMetaCodec.decode(region.metadata)?.name ?: "region-${region.id}"
            OfflineRegionSummary(id = region.id, name = name)
        }
    }

    suspend fun deleteRegion(id: Long) {
        val region = listRegionsRaw().firstOrNull { it.id == id } ?: return
        suspendCancellableCoroutine { cont ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    cont.resume(Unit)
                }

                override fun onError(error: String) {
                    cont.resumeWithException(IllegalStateException(error ?: "delete_failed"))
                }
            })
        }
    }

    private suspend fun listRegionsRaw(): List<OfflineRegion> =
        suspendCancellableCoroutine { cont ->
            manager().listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) {
                    cont.resume((regions ?: emptyArray<OfflineRegion>()).toList())
                }

                override fun onError(error: String) {
                    cont.resumeWithException(IllegalStateException(error ?: "list_failed"))
                }
            })
        }

    companion object {
        /** v1 downloads the whole city extract around the map center (~2.2 km square at Monaco). */
        const val SPAN_DEG: Double = 0.01
    }
}
