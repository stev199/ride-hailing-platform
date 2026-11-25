// Round-12 (CI 45616285742 e2e-cross-actor): the docker build failed with
// "Could not find org.jetbrains.kotlin:kotlin-gradle-plugin-model:1.9.24 -
// Searched in: plugins.gradle.org/m2". With no pluginManagement block the plugin
// classpath resolved from the Gradle Plugin Portal ONLY; the portal 303-redirects
// these kotlin artifacts to Maven Central, and when that edge flakes there is no
// second repo. mavenCentral() in pluginManagement gives the classpath a fallback.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "backend"
