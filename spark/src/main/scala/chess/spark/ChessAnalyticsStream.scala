package chess.spark

import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.{OutputMode, Trigger}
import org.apache.spark.sql.types._

/**
 * Phase 2: Kafka Structured Streaming analytics.
 *
 * Reads live game events from Kafka topics and maintains rolling aggregations.
 *
 * Three streaming queries run concurrently:
 *   1) chess-game-reports  → Rolling win counts, result distribution
 *   2) chess-moves         → Live move frequency analysis
 *   3) chess-evaluations   → Position eval trend monitoring
 *
 * Results are written to:
 *   - Console (for Docker log output)
 *   - JSON files (append mode, partitioned by processing time)
 *   - Postgres (via foreachBatch)
 */
object ChessAnalyticsStream {

  // ── Kafka value schemas (matching the DTOs in KafkaChessStream) ─────────

  val gameReportSchema: StructType = StructType(Seq(
    StructField("gameId",       IntegerType,  nullable = false),
    StructField("totalMoves",   IntegerType,  nullable = false),
    StructField("result",       StringType,   nullable = false),
    StructField("maxEval",      DoubleType,   nullable = false),
    StructField("minEval",      DoubleType,   nullable = false),
    StructField("avgLegal",     DoubleType,   nullable = false),
    StructField("peakMobility", IntegerType,  nullable = false)
  ))

  val moveEventSchema: StructType = StructType(Seq(
    StructField("sessionId", StringType,  nullable = false),
    StructField("move",      StringType,  nullable = false),
    StructField("fenAfter",  StringType,  nullable = false),
    StructField("timestamp", LongType,    nullable = false)
  ))

  val evalEventSchema: StructType = StructType(Seq(
    StructField("sessionId", StringType,  nullable = false),
    StructField("eval",      DoubleType,  nullable = false),
    StructField("timestamp", LongType,    nullable = false)
  ))

  // ────────────────────────────────────────────────────────────────────────────
  // Public API
  // ────────────────────────────────────────────────────────────────────────────

  def run(spark: SparkSession, config: SparkConfig): Unit = {
    println("\n" + "═" * 70)
    println("  ♟  Chess Spark Analytics – Streaming Mode")
    println("═" * 70)

    // Start all three streaming queries concurrently
    startGameReportStream(spark, config)
    startMoveStream(spark, config)
    startEvalStream(spark, config)

    println("\n  🔴 All streams started. Waiting for Kafka events...")
    println("     Press Ctrl+C to stop.\n")

    // Block until any stream terminates (or the process is killed)
    spark.streams.awaitAnyTermination()
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Stream 1: Game Reports → Win counts, result distribution
  // ────────────────────────────────────────────────────────────────────────────

  def startGameReportStream(spark: SparkSession, config: SparkConfig): Unit = {
    import spark.implicits._

    println(s"\n  📡 Starting game-reports stream from '${config.topicGameReports}'...")

    val rawStream = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", config.kafkaBootstrap)
      .option("subscribe", config.topicGameReports)
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .load()

    val gameReports = rawStream
      .selectExpr("CAST(value AS STRING) as json", "timestamp as kafka_ts")
      .select(
        from_json(col("json"), gameReportSchema).alias("data"),
        col("kafka_ts")
      )
      .select("data.*", "kafka_ts")
      .withColumn("winner",
        when(col("result").contains("White"), "White")
          .when(col("result").contains("Black"), "Black")
          .otherwise("Draw/Other")
      )

    // ── Aggregation: Rolling result distribution ────────────────────────
    val resultAgg = gameReports
      .withWatermark("kafka_ts", "1 minute")
      .groupBy(
        window(col("kafka_ts"), "5 minutes", "1 minute"),
        col("winner")
      )
      .agg(
        count("*").alias("Count"),
        avg("totalMoves").alias("AvgMoves"),
        avg("maxEval").alias("AvgMaxEval"),
        avg("avgLegal").alias("AvgBranching")
      )

    // Console output (for Docker logs)
    resultAgg.writeStream
      .queryName("game-reports-console")
      .outputMode(OutputMode.Update())
      .format("console")
      .option("truncate", "false")
      .trigger(Trigger.ProcessingTime("30 seconds"))
      .start()

    // JSON output (append mode with checkpointing)
    resultAgg.writeStream
      .queryName("game-reports-json")
      .outputMode(OutputMode.Append())
      .format("json")
      .option("path", s"${config.outputJsonDir}/stream_game_reports")
      .option("checkpointLocation", s"${config.outputJsonDir}/_checkpoints/game_reports")
      .trigger(Trigger.ProcessingTime("1 minute"))
      .start()

    // Postgres output (via foreachBatch)
    resultAgg.writeStream
      .queryName("game-reports-postgres")
      .outputMode(OutputMode.Update())
      .foreachBatch { (batchDF: DataFrame, batchId: Long) =>
        if (!batchDF.isEmpty) {
          val flat = batchDF
            .withColumn("window_start", col("window.start"))
            .withColumn("window_end", col("window.end"))
            .drop("window")
            .withColumn("batch_id", lit(batchId))

          try {
            flat.write
              .mode(SaveMode.Append)
              .jdbc(config.dbUrl, s"${config.analyticsTable}_stream_results", config.jdbcProperties)
          } catch {
            case e: Exception =>
              println(s"  ⚠  Postgres write failed (batch $batchId): ${e.getMessage}")
          }
        }
      }
      .trigger(Trigger.ProcessingTime("1 minute"))
      .start()
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Stream 2: Chess Moves → Live move frequency
  // ────────────────────────────────────────────────────────────────────────────

  def startMoveStream(spark: SparkSession, config: SparkConfig): Unit = {
    import spark.implicits._

    println(s"  📡 Starting moves stream from '${config.topicMoves}'...")

    val rawStream = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", config.kafkaBootstrap)
      .option("subscribe", config.topicMoves)
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .load()

    val moves = rawStream
      .selectExpr("CAST(value AS STRING) as json", "timestamp as kafka_ts")
      .select(
        from_json(col("json"), moveEventSchema).alias("data"),
        col("kafka_ts")
      )
      .select("data.*", "kafka_ts")

    // ── Aggregation: Move frequency per session (sliding window) ────────
    val moveFrequency = moves
      .withWatermark("kafka_ts", "2 minutes")
      .groupBy(
        window(col("kafka_ts"), "5 minutes", "1 minute"),
        col("sessionId")
      )
      .agg(
        count("*").alias("MoveCount"),
        collect_list("move").alias("Moves")
      )

    // Console output
    moveFrequency.writeStream
      .queryName("moves-console")
      .outputMode(OutputMode.Update())
      .format("console")
      .option("truncate", "false")
      .trigger(Trigger.ProcessingTime("30 seconds"))
      .start()

    // JSON output
    moveFrequency
      .withColumn("Moves", concat_ws(",", col("Moves")))  // flatten array for JSON
      .writeStream
      .queryName("moves-json")
      .outputMode(OutputMode.Append())
      .format("json")
      .option("path", s"${config.outputJsonDir}/stream_moves")
      .option("checkpointLocation", s"${config.outputJsonDir}/_checkpoints/moves")
      .trigger(Trigger.ProcessingTime("1 minute"))
      .start()
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Stream 3: Evaluations → Position eval monitoring
  // ────────────────────────────────────────────────────────────────────────────

  def startEvalStream(spark: SparkSession, config: SparkConfig): Unit = {
    import spark.implicits._

    println(s"  📡 Starting evaluations stream from '${config.topicEvaluations}'...")

    val rawStream = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", config.kafkaBootstrap)
      .option("subscribe", config.topicEvaluations)
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .load()

    val evals = rawStream
      .selectExpr("CAST(value AS STRING) as json", "timestamp as kafka_ts")
      .select(
        from_json(col("json"), evalEventSchema).alias("data"),
        col("kafka_ts")
      )
      .select("data.*", "kafka_ts")

    // ── Aggregation: Eval trend per session (sliding window) ────────────
    val evalTrend = evals
      .withWatermark("kafka_ts", "2 minutes")
      .groupBy(
        window(col("kafka_ts"), "5 minutes", "1 minute"),
        col("sessionId")
      )
      .agg(
        count("*").alias("EvalCount"),
        avg("eval").alias("AvgEval"),
        min("eval").alias("MinEval"),
        max("eval").alias("MaxEval"),
        stddev("eval").alias("EvalVolatility")
      )

    // Console output
    evalTrend.writeStream
      .queryName("evals-console")
      .outputMode(OutputMode.Update())
      .format("console")
      .option("truncate", "false")
      .trigger(Trigger.ProcessingTime("30 seconds"))
      .start()

    // JSON output
    evalTrend.writeStream
      .queryName("evals-json")
      .outputMode(OutputMode.Append())
      .format("json")
      .option("path", s"${config.outputJsonDir}/stream_evaluations")
      .option("checkpointLocation", s"${config.outputJsonDir}/_checkpoints/evaluations")
      .trigger(Trigger.ProcessingTime("1 minute"))
      .start()
  }
}
