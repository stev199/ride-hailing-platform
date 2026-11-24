// Android project root (P2-1a). Standalone gradle root - same pattern as backend/ and mesh-core/
// (repo root has no settings.gradle; CI android-build job runs `cd android && gradle ...`).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_PROJECT (default): session-local sandbox mirror init scripts may add repos
    // (D43 pattern); CI has no init script and resolves against google/mavenCentral only.
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ride-hailing-android"

include(":core-ui")
include(":app-rider")
include(":app-driver")
