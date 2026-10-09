# ============================================================================
# Booking Service - multi-stage production Dockerfile
#   Builder : Maven 3.9 + Eclipse Temurin JDK 21 (Alpine)
#   Runtime : Eclipse Temurin JRE 21 (Alpine), non-root user
# ============================================================================

# ---------------------------------------------------------------- build stage
FROM maven:3.9.16-eclipse-temurin-21-alpine AS builder

WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src/ src/
RUN mvn -B clean package -DskipTests

# --------------------------------------------------------------- runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# curl is required for the container healthcheck
RUN apk add --no-cache curl && \
    addgroup -S -g 1000 appgroup && \
    adduser -S -u 1000 -G appgroup appuser

COPY --from=builder /app/target/booking_service-0.0.1-SNAPSHOT.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

ENV JAVA_OPTS="-Xmx512m -Xms256m"

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=10s --start-period=50s --retries=3 \
    CMD curl -fsS http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]