# --- Build stage -----------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from source for faster rebuilds
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Runtime stage -----------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Baked in at build time by the GitHub Actions workflow (git short SHA).
# Not currently surfaced in the UI, but available for future use (e.g. a
# footer version stamp) without any other change.
ARG APP_VERSION=local-dev
ENV APP_VERSION=${APP_VERSION}

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/target/app.jar ./app.jar
RUN chown app:app ./app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
