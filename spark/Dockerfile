# syntax=docker/dockerfile:1.7
# ─── Stage 1: Build ───────────────────────────────────────────────────────────
#
# The Spark subproject uses Scala 2.13 (Spark does not support Scala 3).
# We use the SBT image to build a fat JAR via sbt-assembly.
#
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.12.9_3.3.7 AS builder

WORKDIR /app

COPY project/ project/
COPY build.sbt .

# The spark subproject is standalone (Scala 2.13, no dependsOn other modules)
COPY spark/ spark/

# Build fat JAR – reuse ivy/sbt cache via BuildKit cache mount
RUN --mount=type=cache,target=/root/.ivy2/cache \
    --mount=type=cache,target=/root/.sbt \
    SBT_OPTS="-Xmx2G -Xss2M -Dsbt.supershell=false" sbt spark/assembly

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
#
# Use the official Spark image which includes PySpark + Spark binaries.
# This allows Spark-on-K8s features (dynamic allocation, executor pods).
#
FROM apache/spark:3.5.3-java17

USER root

# Create app directory and output volume mount point
RUN mkdir -p /app/output && chown spark:spark /app /app/output

WORKDIR /app

# Copy the fat JAR from the build stage
COPY --from=builder --chown=spark:spark \
    /app/spark/target/scala-2.13/chess-spark-assembly-*.jar app.jar

# Copy the puzzle CSV (will be large – consider mounting as a volume instead)
# COPY --chown=spark:spark lichess_db_puzzle.csv ./

USER spark

# Default environment
ENV SPARK_MASTER="local[*]"
ENV SPARK_OUTPUT_DIR="/app/output"
ENV PUZZLE_CSV_PATH="/app/data/lichess_db_puzzle.csv"

# Volume for output data (JSON files, checkpoints)
VOLUME ["/app/output", "/app/data"]

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", \
            "-cp", "app.jar", "chess.spark.SparkMain"]

# Default argument: run all (batch + streaming)
CMD ["all"]
