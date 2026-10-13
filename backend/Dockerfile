# One Temurin release for both stages, so the JDK that compiles the jar and the JRE that runs it are the
# same release (ADR-030).
FROM eclipse-temurin:21.0.12.1_1-jdk-alpine-3.24 AS build
WORKDIR /app
# The Maven wrapper downloads the version the repository pins and checks its checksum (ADR-030), with
# the wget, unzip and sha256sum this image already has - no package to install. chmod so the build does
# not depend on the context keeping the executable bit git records; an archive download or a plain copy
# can drop it.
COPY mvnw ./
COPY .mvn ./.mvn
COPY pom.xml .
COPY src ./src
RUN chmod +x mvnw && ./mvnw -B --no-transfer-progress package -DskipTests

FROM eclipse-temurin:21.0.12.1_1-jre-alpine-3.24
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080

# Declared on the image rather than only in docker-compose, so anything that runs this container knows
# how to ask whether it is working. Readiness, not the aggregate health endpoint: this answers "can it
# serve traffic", which is the question a scheduler is actually asking. start-period covers JVM start
# and Flyway migration, during which an unhealthy answer is correct and must not count against it.
# wget is busybox's, already in the alpine image - no package to install.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3     CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
