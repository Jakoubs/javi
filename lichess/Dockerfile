# syntax=docker/dockerfile:1.7
# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.12.9_3.3.7 AS builder

WORKDIR /app

COPY project/ project/
COPY build.sbt .

# lichess depends on model, util, ai
COPY model/   model/
COPY util/    util/
COPY ai/      ai/
COPY lichess/ lichess/

# Build fat JAR – reuse ivy/sbt cache via BuildKit cache mount
RUN --mount=type=cache,target=/root/.ivy2/cache \
    --mount=type=cache,target=/root/.sbt \
    sbt lichess/assembly

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

# Run as non-root for security
RUN groupadd --system chess && useradd --system --gid chess chess
WORKDIR /app
RUN chown chess:chess /app

COPY --from=builder --chown=chess:chess \
    /app/lichess/target/scala-3.3.4/chess-lichess-assembly-*.jar app.jar
COPY --chown=chess:chess ai_weights*.txt ./

USER chess

# Lichess API token – supply via docker run -e LICHESS_TOKEN=xxx or .env file
ENV LICHESS_TOKEN=""

# The lichess bot connects outbound only; no port to expose
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
