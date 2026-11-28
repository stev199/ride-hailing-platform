// Round-36 (CI 46114791648 mesh-core-sim 429): plugin/dependency resolution hits
// repo.maven.apache.org rate limits on runner IPs; the Google mirror comes before
// mavenCentral so the flaky edge is the last resort (backend round-12/36 parity).
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "mesh-core"
