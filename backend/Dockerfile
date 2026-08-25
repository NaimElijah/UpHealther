FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn package -DskipTests

FROM eclipse-temurin:21-jre-alpine
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
