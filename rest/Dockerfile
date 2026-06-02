# syntax=docker/dockerfile:1.7
# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.12.9_3.3.7 AS builder

WORKDIR /app

# Copy sbt project definition first for better layer caching
COPY project/ project/
COPY build.sbt .

# Copy only the modules needed for rest (model, util, ai, controller, rest)
COPY model/      model/
COPY util/       util/
COPY ai/         ai/
COPY controller/ controller/
COPY rest/       rest/

# Build fat JAR – reuse ivy/sbt cache via BuildKit cache mount
RUN --mount=type=cache,target=/root/.ivy2/cache \
    --mount=type=cache,target=/root/.sbt \
    sbt rest/assembly

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

# Install curl for healthcheck (minimal footprint)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as non-root for security
RUN groupadd --system chess && useradd --system --gid chess chess
WORKDIR /app
RUN chown chess:chess /app

COPY --from=builder --chown=chess:chess \
    /app/rest/target/scala-3.3.4/chess-rest-assembly-*.jar app.jar

# Copy AI weight files needed at runtime
COPY --chown=chess:chess ai_weights*.txt ./

USER chess

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
