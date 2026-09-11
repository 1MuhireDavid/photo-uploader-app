# --- Build stage -----------------------------------------------------
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /build

# Cache dependencies separately from source for faster rebuilds
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Runtime stage -----------------------------------------------------
# Amazon Corretto (AWS's own OpenJDK build), pulled from the Amazon ECR
# Public Gallery -- no Docker Hub pull-rate limits to worry about in CI.
# NOTE: the Public Gallery only mirrors the Amazon Linux 2023 (glibc)
# tags, not the Alpine (musl) ones -- those are Docker-Hub-only. Hence
# plain "21" here instead of "21-alpine", and groupadd/useradd below
# instead of Alpine's addgroup/adduser.
FROM public.ecr.aws/amazoncorretto/amazoncorretto:21
WORKDIR /app

RUN groupadd -r app && useradd -r -g app app
COPY --from=build /build/target/app.jar ./app.jar
RUN chown app:app ./app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
