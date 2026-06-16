package chess.spark

import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import java.util.Properties

/**
 * Phase 1: Batch analytics on historical chess data.
 *
 * Two data sources:
 *   A) Lichess puzzle CSV   (file-based, ~830 MB)
 *   B) PostgreSQL tables    (games, move_events, users)
 *
 * Results are written to:
 *   1) Console (stdout)
 *   2) JSON files (spark-output/)
 *   3) Postgres analytics table
 */
object ChessAnalyticsBatch {

  // ─── CSV schema for lichess_db_puzzle.csv ────────────────────────────────
  //
  // Header: PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags
  //
  val puzzleSchema: StructType = StructType(Seq(
    StructField("PuzzleId",         StringType,  nullable = false),
    StructField("FEN",              StringType,  nullable = false),
    StructField("Moves",            StringType,  nullable = false),
    StructField("Rating",           IntegerType, nullable = false),
    StructField("RatingDeviation",  IntegerType, nullable = true),
    StructField("Popularity",       IntegerType, nullable = true),
    StructField("NbPlays",          IntegerType, nullable = true),
    StructField("Themes",           StringType,  nullable = true),
    StructField("GameUrl",          StringType,  nullable = true),
    StructField("OpeningTags",      StringType,  nullable = true)
  ))

  // ────────────────────────────────────────────────────────────────────────────
  // Public API
  // ────────────────────────────────────────────────────────────────────────────

  def run(spark: SparkSession, config: SparkConfig): Unit = {
    println("\n" + "═" * 70)
    println("  ♟  Chess Spark Analytics – Batch Mode")
    println("═" * 70)

    // ── Phase 1a: Puzzle CSV Analysis ────────────────────────────────────
    if (config.enablePuzzle) {
      println("\n── Phase 1a: Lichess Puzzle CSV Analysis ──────────────────────────\n")
      runPuzzleAnalysis(spark, config)
    } else {
      println("\n── Phase 1a: Lichess Puzzle CSV Analysis (Skipped/Disabled) ───────\n")
    }

    // ── Phase 1b: Postgres Game Analysis ─────────────────────────────────
    println("\n── Phase 1b: PostgreSQL Game Analysis ─────────────────────────────\n")
    runGameAnalysis(spark, config)
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Phase 1a: Puzzle CSV
  // ────────────────────────────────────────────────────────────────────────────

  def runPuzzleAnalysis(spark: SparkSession, config: SparkConfig): Unit = {
    import spark.implicits._

    val puzzles = spark.read
      .option("header", "true")
      .schema(puzzleSchema)
      .csv(config.puzzleCsvPath)

    val totalPuzzles = puzzles.count()
    println(s"  📦 Total puzzles loaded: $totalPuzzles")

    // ── 1. Rating distribution ──────────────────────────────────────────
    println("\n  📊 Puzzle Rating Distribution (buckets of 200):")
    val ratingDist = puzzles
      .withColumn("RatingBucket", (floor(col("Rating") / 200) * 200).cast(IntegerType))
      .groupBy("RatingBucket")
      .agg(
        count("*").alias("Count"),
        avg("NbPlays").alias("AvgPlays"),
        avg("Popularity").alias("AvgPopularity")
      )
      .orderBy("RatingBucket")

    ratingDist.show(30, truncate = false)
    writeOutput(ratingDist, config, "puzzle_rating_distribution")

    // ── 2. Most popular themes ──────────────────────────────────────────
    println("\n  🏷️  Top 20 Puzzle Themes:")
    val themeCounts = puzzles
      .filter(col("Themes").isNotNull && col("Themes") =!= "")
      .withColumn("Theme", explode(split(col("Themes"), " ")))
      .groupBy("Theme")
      .agg(
        count("*").alias("Count"),
        avg("Rating").alias("AvgRating"),
        avg("NbPlays").alias("AvgPlays")
      )
      .orderBy(desc("Count"))

    themeCounts.show(20, truncate = false)
    writeOutput(themeCounts, config, "puzzle_theme_analysis")

    // ── 3. Difficulty analysis ──────────────────────────────────────────
    println("\n  🎯 Puzzle Difficulty Analysis:")
    val difficultyStats = puzzles
      .withColumn("MoveCount", size(split(col("Moves"), " ")))
      .withColumn("Difficulty", when(col("Rating") < 1200, "Beginner")
        .when(col("Rating") < 1600, "Intermediate")
        .when(col("Rating") < 2000, "Advanced")
        .when(col("Rating") < 2400, "Expert")
        .otherwise("Master"))
      .groupBy("Difficulty")
      .agg(
        count("*").alias("Count"),
        avg("Rating").alias("AvgRating"),
        avg("MoveCount").alias("AvgMoves"),
        avg("NbPlays").alias("AvgPlays"),
        avg("Popularity").alias("AvgPopularity")
      )
      .orderBy("AvgRating")

    difficultyStats.show(truncate = false)
    writeOutput(difficultyStats, config, "puzzle_difficulty_analysis")

    // ── 4. Opening-based puzzle analysis ────────────────────────────────
    println("\n  📖 Top 15 Opening Tags in Puzzles:")
    val openingAnalysis = puzzles
      .filter(col("OpeningTags").isNotNull && col("OpeningTags") =!= "")
      .withColumn("Opening", explode(split(col("OpeningTags"), " ")))
      .groupBy("Opening")
      .agg(
        count("*").alias("Count"),
        avg("Rating").alias("AvgRating"),
        min("Rating").alias("MinRating"),
        max("Rating").alias("MaxRating")
      )
      .orderBy(desc("Count"))

    openingAnalysis.show(15, truncate = false)
    writeOutput(openingAnalysis, config, "puzzle_opening_analysis")

    // ── 5. Summary stats ────────────────────────────────────────────────
    println("\n  📈 Overall Puzzle Statistics:")
    val summary = puzzles.agg(
      count("*").alias("TotalPuzzles"),
      avg("Rating").alias("AvgRating"),
      min("Rating").alias("MinRating"),
      max("Rating").alias("MaxRating"),
      stddev("Rating").alias("StdDevRating"),
      avg("NbPlays").alias("AvgPlays"),
      sum("NbPlays").alias("TotalPlays")
    )
    summary.show(truncate = false)
    writeOutput(summary, config, "puzzle_summary")
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Phase 1b: Postgres game data
  // ────────────────────────────────────────────────────────────────────────────

  def runGameAnalysis(spark: SparkSession, config: SparkConfig): Unit = {
    import spark.implicits._

    val games = readTable(spark, config, "games")

    if (games.isEmpty) {
      println("  ⚠  No games found in PostgreSQL – skipping game analysis.")
      println("     (Start some games via the REST API first.)")
      return
    }

    val gamesDF = games.cache()
    val totalGames = gamesDF.count()
    println(s"  📦 Total games loaded: $totalGames")

    // ── 1. Win/Draw/Loss distribution ───────────────────────────────────
    println("\n  🏆 Game Results Distribution:")
    val resultDist = gamesDF
      .groupBy("result")
      .agg(
        count("*").alias("Count"),
        round(count("*") * 100.0 / totalGames, 1).alias("Percentage")
      )
      .orderBy(desc("Count"))

    resultDist.show(truncate = false)
    writeOutput(resultDist, config, "game_results")

    // ── 2. Games over time ──────────────────────────────────────────────
    println("\n  📅 Games Over Time (by day):")
    val gamesOverTime = gamesDF
      .withColumn("Date", to_date(from_unixtime(col("created_at") / 1000)))
      .groupBy("Date")
      .agg(
        count("*").alias("GamesPlayed"),
        sum(when(col("result") === "white", 1).otherwise(0)).alias("WhiteWins"),
        sum(when(col("result") === "black", 1).otherwise(0)).alias("BlackWins"),
        sum(when(col("result") === "draw",  1).otherwise(0)).alias("Draws")
      )
      .orderBy("Date")

    gamesOverTime.show(30, truncate = false)
    writeOutput(gamesOverTime, config, "games_over_time")

    // ── 3. Average game duration ────────────────────────────────────────
    println("\n  ⏱  Game Duration Analysis:")
    val moveEvents = readTable(spark, config, "move_events")

    if (!moveEvents.isEmpty) {
      val movesDF = moveEvents.cache()

      val gameDuration = movesDF
        .groupBy("game_id")
        .agg(
          max("move_number").alias("TotalMoves"),
          (max("timestamp") - min("timestamp")).alias("DurationMs")
        )
        .agg(
          avg("TotalMoves").alias("AvgMoves"),
          min("TotalMoves").alias("MinMoves"),
          max("TotalMoves").alias("MaxMoves"),
          avg("DurationMs").alias("AvgDurationMs")
        )

      gameDuration.show(truncate = false)
      writeOutput(gameDuration, config, "game_duration")

      // ── 4. Most popular first moves ─────────────────────────────────
      println("\n  ♟  Most Popular Opening Moves:")
      val firstMoves = movesDF
        .filter(col("move_number") === 0)
        .groupBy("san")
        .agg(count("*").alias("Count"))
        .orderBy(desc("Count"))

      firstMoves.show(10, truncate = false)
      writeOutput(firstMoves, config, "popular_first_moves")

      movesDF.unpersist()
    }

    // ── 5. User statistics (if users table has data) ────────────────────
    println("\n  👤 User Statistics:")
    val users = readTable(spark, config, "users")

    if (!users.isEmpty) {
      val userStats = users.agg(
        count("*").alias("TotalUsers"),
        sum(when(col("is_verified"), 1).otherwise(0)).alias("VerifiedUsers"),
        sum(when(col("is_banned"),   1).otherwise(0)).alias("BannedUsers")
      )
      userStats.show(truncate = false)
      writeOutput(userStats, config, "user_statistics")
    } else {
      println("  ⚠  No users found in PostgreSQL.")
    }

    gamesDF.unpersist()
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ────────────────────────────────────────────────────────────────────────────

  /** Read a table from Postgres via JDBC. Returns an empty DataFrame on error. */
  private def readTable(spark: SparkSession, config: SparkConfig, table: String): DataFrame = {
    try {
      spark.read.jdbc(config.dbUrl, table, config.jdbcProperties)
    } catch {
      case e: Exception =>
        println(s"  ⚠  Could not read table '$table': ${e.getMessage}")
        spark.emptyDataFrame
    }
  }

  /**
   * Write analysis results to all three output sinks:
   *   1) Console  (already shown via .show())
   *   2) JSON files
   *   3) Postgres analytics table
   */
  private def writeOutput(df: DataFrame, config: SparkConfig, name: String): Unit = {
    // ── JSON output ─────────────────────────────────────────────────────
    val jsonPath = s"${config.outputJsonDir}/$name"
    try {
      df.coalesce(1)
        .write
        .mode(SaveMode.Overwrite)
        .json(jsonPath)
      println(s"  💾 JSON → $jsonPath/")
    } catch {
      case e: Exception =>
        println(s"  ⚠  JSON write failed for '$name': ${e.getMessage}")
    }

    // ── Postgres output ─────────────────────────────────────────────────
    val pgTable = s"${config.analyticsTable}_$name"
    try {
      df.write
        .mode(SaveMode.Overwrite)
        .jdbc(config.dbUrl, pgTable, config.jdbcProperties)
      println(s"  💾 Postgres → $pgTable")
    } catch {
      case e: Exception =>
        println(s"  ⚠  Postgres write failed for '$pgTable': ${e.getMessage}")
    }
  }
}
