plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
    jacoco
}

group = "com.ridehailing"
version = "0.1.0"

repositories {
    // Round-36: Google's central mirror first - repo.maven.apache.org 429s runner
    // IPs under load (CodeQL analyze-kotlin CI 46132637884); Maven Central itself
    // stays as the last-resort fallback (local init-script parity, D27).
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

val ktorVersion = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    // JWT issuance/verification (P1-2): com.auth0 java-jwt, HS256, typ=access claims
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.flywaydb:flyway-core:9.22.3")
    // argon2id password hashing (P1-2). JWT via com.auth0 java-jwt (single JWT
    // implementation across the pipeline).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // OTP adapter transports (P1-2): Telegram Bot API + self-hosted HTTP SMS gateway
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    // SMTP email adapter (P1-2)
    implementation("com.sun.mail:jakarta.mail:2.0.1")
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    // ContractAlignmentTest (D49) reads docs/api/openapi.yaml to assert that the
    // documented constraints exactly mirror the enforced validation rules.
    testImplementation("org.yaml:snakeyaml:2.2")
}

application {
    mainClass.set("com.ridehailing.backend.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    // Exact exception + stack trace on CI: PG-backed tests can only run there
    // (sandbox has no Docker), so failures must be diagnosable from the log alone.
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
