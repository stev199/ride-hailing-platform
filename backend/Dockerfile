# Multi-stage build: compile with Gradle, run on a slim JRE.
FROM gradle:8.10.2-jdk17 AS build
WORKDIR /home/gradle/project
COPY . .
RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /home/gradle/project/build/install/backend/ ./
EXPOSE 8080
CMD ["./bin/backend"]
