// Round-12 (CI 45616285742 e2e-cross-actor): the docker build failed with
// "Could not find org.jetbrains.kotlin:kotlin-gradle-plugin-model:1.9.24 -
// Searched in: plugins.gradle.org/m2". With no pluginManagement block the plugin
// classpath resolved from the Gradle Plugin Portal ONLY; the portal 303-redirects
// these kotlin artifacts to Maven Central, and when that edge flakes there is no
// second repo. mavenCentral() in pluginManagement gives the classpath a fallback.
//
// Round-36 (CI 46132637884 CodeQL analyze-kotlin + 46114791648 mesh-core-sim):
// the fallback itself 429s - repo.maven.apache.org rate-limits runner IPs under
// load, and plugin-resolution failures fail whole workflows with no code defect.
// Google's central mirror is listed BEFORE mavenCentral so the flaky edge is the
// last resort, not the default (same mirror the local init script uses, D27).
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "backend"
