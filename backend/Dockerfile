# Multi-stage build: compile with Gradle, run on a slim JRE.
FROM gradle:8.10.2-jdk17 AS build
WORKDIR /home/gradle/project
COPY . .
# Maven Central intermittently 429s CI runners (seen 2026-11-24, run 45489463997):
# one bounded retry with a pause keeps the docker build resilient without a mirror.
RUN gradle --no-daemon installDist || (sleep 30 && gradle --no-daemon installDist)

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /home/gradle/project/build/install/backend/ ./
EXPOSE 8080
CMD ["./bin/backend"]
