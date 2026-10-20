# Stage 1: build with the pinned Maven wrapper
FROM maven:3-eclipse-temurin-21 AS build

WORKDIR /build

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Source last so the dependency layer stays cached until the pom changes.
COPY src ./src

# MAVEN_CONFIG is cleared because the official Maven image sets it to
# /root/.m2, which the wrapper would forward as a lifecycle phase.
RUN chmod +x mvnw && MAVEN_CONFIG= ./mvnw -B package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /build/target/*.jar app.jar

# Documentation only. The app binds SERVER_PORT when set, otherwise PORT,
# otherwise 8002.
EXPOSE 8002

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
