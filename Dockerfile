# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM first: this layer is cached as long as dependencies do not change,
# so code edits do not re-download the internet on every build.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Do not run as root.
RUN addgroup -S app && adduser -S app -G app

COPY --from=build /build/target/booking-api-*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
