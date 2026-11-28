plugins {
    kotlin("jvm") version "1.9.24"
}

group = "com.ridehailing"
version = "0.1.0"

repositories {
    // Round-36: Google's central mirror first - repo.maven.apache.org 429s runner
    // IPs under load (mesh-core-sim CI 46114791648); Maven Central stays as the
    // last-resort fallback (backend round-36 parity).
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true // scenario diagnostics surface in CI logs
    }
}

/**
 * GATE 3 evidence + regression gate (Part C Stage 3): runs the 15-scenario suite,
 * writes /docs/evidence/mesh-sim (metrics table + charts) and enforces baseline.json
 * (>10% metric regression fails the build). First run initializes baseline.json.
 * Update an intentionally improved baseline with: gradle simReport -PupdateBaseline
 */
tasks.register<JavaExec>("simReport") {
    group = "verification"
    description = "GATE 3 mesh simulation suite: evidence + baseline regression gate"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ridehailing.mesh.sim.ScenarioRunnerKt")
    workingDir = projectDir
    args = buildList {
        add("--evidence"); add("../docs/evidence/mesh-sim")
        add("--baseline"); add("baseline.json")
        if (project.hasProperty("updateBaseline")) add("--update-baseline")
    }
}

dependencies {
    testImplementation(kotlin("test"))
}
