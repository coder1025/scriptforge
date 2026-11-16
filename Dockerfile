FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress clean verify

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system scriptforge && useradd --system --gid scriptforge --home-dir /app scriptforge \
    && mkdir -p /app/data && chown -R scriptforge:scriptforge /app
WORKDIR /app
COPY --from=build --chown=scriptforge:scriptforge /workspace/target/scriptforge-0.1.0.jar /app/app.jar
USER scriptforge
ENV SERVER_ADDRESS=0.0.0.0 \
    PORT=8080 \
    SCRIPT_FORGE_DATA_DIR=/app/data
EXPOSE 8080
VOLUME ["/app/data"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

