FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app
COPY . .
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core && ./mvnw clean package -DskipTests=true || mvn clean package -DskipTests=true

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/weekly-roster-management-system-1.0.0.jar app.jar
ENV JAVA_OPTS="-Djava.awt.headless=true"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar app.jar"]
