package com.ridehailing.core.ui

/**
 * Platform-agnostic actor mode shared by the rider and driver shells (P2-1a).
 * Pure Kotlin so JVM unit tests can pin the defaults; Compose shells map it to
 * localized string resources.
 */
enum class AppMode(val defaultLabel: String) {
    RIDER("Rider"),
    DRIVER("Driver"),
    ;

    companion object {
        const val CURRENT_SCHEMA: Int = 1
    }
}
