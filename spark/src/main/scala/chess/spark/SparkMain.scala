package chess.spark

import org.apache.spark.sql.SparkSession

/**
 * Entry point for the Chess Spark Analytics module.
 *
 * Modes:
 *   - `batch`  : Run Phase 1 batch analytics (puzzle CSV + Postgres), then exit.
 *   - `stream` : Run Phase 2 Kafka Structured Streaming, blocks until stopped.
 *   - `all`    : Run batch first, then start streaming (default).
 *
 * Usage:
 *   sbt "spark/run"                         → runs "all" mode
 *   sbt "spark/runMain chess.spark.SparkMain batch"
 *   sbt "spark/runMain chess.spark.SparkMain stream"
 *
 * In Docker:
 *   java -cp app.jar chess.spark.SparkMain all
 */
object SparkMain {

  def main(args: Array[String]): Unit = {
    val mode = args.headOption.getOrElse("all").toLowerCase

    println()
    println("╔══════════════════════════════════════════════════════════════════╗")
    println("║           ♟  Chess Spark Analytics Engine  ♟                    ║")
    println("╚══════════════════════════════════════════════════════════════════╝")
    println(s"  Mode: $mode")

    val config = SparkConfig.load()

    println(s"  DB:   ${config.dbUrl}")
    println(s"  Kafka: ${config.kafkaBootstrap}")
    println(s"  CSV:  ${config.puzzleCsvPath}")
    println(s"  Out:  ${config.outputJsonDir}")
    println()

    val spark = SparkSession.builder()
      .appName("ChessAnalytics")
      .master(sys.env.getOrElse("SPARK_MASTER", "local[*]"))
      // ── Dynamic allocation on Kubernetes ─────────────────────────────
      // When running on K8s, Spark can request/release executor pods
      // dynamically based on workload. These settings are only active when
      // SPARK_MASTER is set to "k8s://..." (ignored in local mode).
      .config("spark.dynamicAllocation.enabled",                    "true")
      .config("spark.dynamicAllocation.shuffleTracking.enabled",    "true")
      .config("spark.dynamicAllocation.minExecutors",               "1")
      .config("spark.dynamicAllocation.maxExecutors",               "4")
      .config("spark.dynamicAllocation.executorIdleTimeout",        "60s")
      .config("spark.dynamicAllocation.schedulerBacklogTimeout",    "10s")
      // ── K8s executor pod template ────────────────────────────────────
      .config("spark.kubernetes.container.image",                   sys.env.getOrElse("SPARK_K8S_IMAGE", "javi-spark:local"))
      .config("spark.kubernetes.namespace",                         "javi")
      .config("spark.kubernetes.authenticate.driver.serviceAccountName", "spark")
      // ── Spark SQL / Streaming tuning ─────────────────────────────────
      .config("spark.sql.adaptive.enabled",                         "true")
      .config("spark.sql.streaming.schemaInference",                "true")
      .config("spark.serializer",                                   "org.apache.spark.serializer.KryoSerializer")
      .config("spark.sql.shuffle.partitions",                       "8")
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")

    try {
      mode match {
        case "batch"  =>
          ChessAnalyticsBatch.run(spark, config)
          println("\n✅ Batch analysis complete.")

        case "stream" =>
          ChessAnalyticsStream.run(spark, config)
          // Blocks until streams terminate

        case "all" | _ =>
          // Run batch first, then switch to streaming
          ChessAnalyticsBatch.run(spark, config)
          println("\n✅ Batch analysis complete. Switching to streaming mode...\n")
          ChessAnalyticsStream.run(spark, config)
      }
    } finally {
      spark.stop()
      println("\n♟  Spark session stopped. Goodbye!")
    }
  }
}
