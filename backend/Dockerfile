# Multi-stage build: compile with Gradle, run on a slim JRE.
FROM gradle:8.10.2-jdk17 AS build
WORKDIR /home/gradle/project
COPY . .
# Maven Central intermittently 429s/404s CI runners (seen 2026-11-24 run 45489463997
# and 2026-11-25 run 45616285742: the gradle-plugin-portal -> maven-central redirect
# chain broke, killing the plugin classpath resolution). Two layers of resilience:
# Gradle's internal repo retry flags (bounded retries with backoff on 4xx/5xx) and
# one full-build retry with a pause. settings.gradle.kts also adds mavenCentral()
# to pluginManagement so the classpath has a fallback repo.
RUN gradle --no-daemon -Dorg.gradle.internal.repository.max.tentatives=10 -Dorg.gradle.internal.repository.initial.backoff=2000 installDist \
  || (sleep 30 && gradle --no-daemon -Dorg.gradle.internal.repository.max.tentatives=10 -Dorg.gradle.internal.repository.initial.backoff=2000 installDist)

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /home/gradle/project/build/install/backend/ ./
EXPOSE 8080
CMD ["./bin/backend"]
