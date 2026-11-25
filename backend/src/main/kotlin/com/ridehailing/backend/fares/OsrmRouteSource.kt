package com.ridehailing.backend.fares

/**
 * P4-1 seam between the fare service and the route source (spec §pricing:
 * online estimates use OSRM distance/time). Production wires [OsrmClient]
 * (self-hosted osrm-routed); tests stub this. Returns null on ANY failure -
 * callers degrade to the calibrated straight-line fallback.
 */
fun interface OsrmRouteSource {
    fun route(lat1: Double, lon1: Double, lat2: Double, lon2: Double): OsrmClient.Route?
}
