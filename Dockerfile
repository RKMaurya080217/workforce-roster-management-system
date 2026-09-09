# Step 1: Build the application with official Maven & OpenJDK 17
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Package production executable JAR (tests skipped during image build)
RUN mvn clean package -DskipTests=true

# Step 2: Production JRE 17 Runtime with Linux fonts for AWT headless export
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install fontconfig and DejaVu TrueType fonts for headless image export support
RUN apt-get update && \
    apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core && \
    rm -rf /var/lib/apt/lists/*

# Copy the built JAR from builder stage
COPY --from=builder /app/target/weekly-roster-management-system-1.0.0.jar app.jar

# Container-aware JVM memory & CPU optimization for Railway (SerialGC + Dynamic RAM bounds)
ENV JAVA_OPTS="-Djava.awt.headless=true -XX:+UseSerialGC -XX:MaxRAMPercentage=65.0 -XX:InitialRAMPercentage=25.0 -XX:MinRAMPercentage=25.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# Default container port
EXPOSE 8080

# Launch Spring Boot with dynamic Railway $PORT resolution
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]
