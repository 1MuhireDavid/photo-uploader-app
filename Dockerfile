# --- Build stage (compile and package) -----------------------------------------------------
# maven to build the project, naming it build allows
# referencing its outputs in later steps, 
# Set /build as a working directory to avoid polluting the root filesystem with build artifacts.
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /build

# Cache dependencies separately from source for faster rebuilds
# copy pom.xml into container and download dependencies required for the project
COPY pom.xml .
RUN mvn -B dependency:go-offline

# copy source code into container and build the project and package into a runnable jar file
# skip tests to speed up the build process
COPY src ./src
RUN mvn -B clean package -DskipTests


# --- Runtime stage (execution environment & reduced image size) -----------------------------------------------------
# Amazon Corretto (AWS's own OpenJDK build), pulled from the Amazon ECR
# Sets the working directory to /app
FROM public.ecr.aws/amazoncorretto/amazoncorretto:21
WORKDIR /app

# create a non-root user to run the application for security reasons
# append new system group and user
RUN echo "app:x:1000:" >> /etc/group && \
    echo "app:x:1000:1000::/nonexistent:/sbin/nologin" >> /etc/passwd

#copy only compiled jar file from build stage
#grant ownership of the jar file to the non-root user
# Switches the active execution user to app for all subsequent operations
COPY --from=build /build/target/app.jar ./app.jar
RUN chown app:app ./app.jar
USER app

#documents the app listens on that 8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
