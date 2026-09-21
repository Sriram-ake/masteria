# Multi-stage Dockerfile for the Masteria Ktor backend (Render deployment).
# Build context is the REPO ROOT, because the image bundles content/:
#   docker build -f backend/Dockerfile -t masteria-backend .

# Stage 1: build the fat JAR with JDK 17
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

COPY content ./content
COPY backend ./backend

WORKDIR /src/backend

# gradlew is checked out with Windows (CRLF) line endings on the dev PC; strip them or /bin/sh fails.
RUN sed -i 's/$//' gradlew && chmod +x gradlew && ./gradlew buildFatJar --no-daemon -q

# Stage 2: slim runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd --system --uid 10001 masteria && mkdir -p /app/data && chown masteria /app/data
COPY --from=build /src/backend/build/libs/masteria-backend.jar ./masteria-backend.jar

# Render sets PORT at runtime; 8080 is the local fallback.
ENV PORT=8080     DATA_DIR=/app/data     TIMEZONE=Asia/Kolkata     JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

USER masteria
EXPOSE 8080

CMD ["java", "-jar", "masteria-backend.jar"]
