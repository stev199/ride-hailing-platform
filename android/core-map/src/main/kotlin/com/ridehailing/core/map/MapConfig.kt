package com.ridehailing.core.map

/**
 * Configuration for every map surface (P2-2). The style URL points at the SELF-HOSTED
 * tileserver-gl instance (infra compose geo profile, OpenMapTiles MBTiles built by
 * infra/fetch-map-data.sh) - apps carry it in BuildConfig (MAP_STYLE_URL), overridable
 * per CI run with -Pmap.styleUrl=. No third-party tile hosts anywhere (spec hard rule).
 */
data class MapConfig(
    val styleUrl: String,
    /** Initial camera center (defaults to the Monaco city extract used in dev/CI). */
    val centerLat: Double = DEFAULT_CENTER_LAT,
    val centerLng: Double = DEFAULT_CENTER_LNG,
    val zoom: Double = DEFAULT_ZOOM,
) {
    init {
        require(styleUrl.isNotBlank()) { "map styleUrl must not be blank" }
    }

    companion object {
        const val DEFAULT_CENTER_LAT: Double = 43.7384
        const val DEFAULT_CENTER_LNG: Double = 7.4246
        const val DEFAULT_ZOOM: Double = 12.5

        /** Emulator -> host loopback: the compose geo profile serves tiles on :8081. */
        const val EMULATOR_STYLE_URL: String =
            "http://10.0.2.2:8081/styles/basic/style.json"
    }
}
