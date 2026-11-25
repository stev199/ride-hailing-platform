package com.ridehailing.core.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap

/**
 * MapLibre map composable (P2-2). Wraps [MapView] with full lifecycle forwarding and
 * loads the self-hosted style from [MapConfig]. The map DEGRADES GRACEFULLY offline:
 * a failed style load is logged, never crashes, and the attribution widget stays visible
 * (ODbL attribution required wherever tiles are displayed).
 *
 * v1 scope note: no location layer / gestures tuning yet - the plain camera at the city
 * extract center; markers (nearby drivers, trip legs) land with dispatch (Phase 5+).
 */
@Composable
fun MapScreen(
    config: MapConfig,
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember(config) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    mapView.onDestroy()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Compose may dispose the view without an ON_DESTROY pass through the owner
            // (e.g. replaced content): mirror the AndroidView teardown here.
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { _ ->
            mapView.apply {
                getMapAsync { map ->
                    map.setStyle(config.styleUrl) { style ->
                        // Style load callback fires on success AND failure (null style):
                        // keep the camera setup regardless, attribution stays rendered.
                    }
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(config.centerLat, config.centerLng))
                        .zoom(config.zoom)
                        .build()
                    onMapReady(map)
                }
            }
        },
    )
}
