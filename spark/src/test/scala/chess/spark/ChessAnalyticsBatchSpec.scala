package chess.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.{File, PrintWriter}
import java.nio.file.Files

/**
 * Unit tests for ChessAnalyticsBatch.
 *
 * Uses a local SparkSession with a small inline CSV to validate
 * the puzzle analysis pipeline without requiring Postgres or the
 * full 830 MB puzzle file.
 */
class ChessAnalyticsBatchSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  // Use a stable val so that `sparkSession.implicits._` works in Scala 2.13
  @transient var sparkSession: SparkSession = _
  var tempDir: File = _
  var csvFile: File = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    sparkSession = SparkSession.builder()
      .appName("ChessAnalyticsBatchSpec")
      .master("local[2]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "2")
      .getOrCreate()
    sparkSession.sparkContext.setLogLevel("ERROR")

    // Create a temp directory for output
    tempDir = Files.createTempDirectory("spark-test-output").toFile
    tempDir.deleteOnExit()

    // Create a small test CSV matching the lichess puzzle format
    csvFile = new File(tempDir, "test_puzzles.csv")
    val writer = new PrintWriter(csvFile)
    writer.println("PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags")
    writer.println("00001,rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1,e7e5 d2d4,1200,80,90,5000,opening short,https://lichess.org/test1,Kings_Pawn_Game")
    writer.println("00002,rnbqkbnr/pppppppp/8/8/3PP3/8/PPP2PPP/RNBQKBNR b KQkq - 0 2,d7d5 e4d5,1500,75,85,3000,middlegame advantage,https://lichess.org/test2,Queens_Gambit")
    writer.println("00003,r1bqkbnr/pppppppp/2n5/8/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 2 2,e7e5 d2d4,800,90,95,8000,crushing short,https://lichess.org/test3,Kings_Pawn_Game")
    writer.println("00004,rnbqkb1r/pppppppp/5n2/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 1 2,e4e5 f6d5,2200,60,70,1000,endgame long,https://lichess.org/test4,")
    writer.println("00005,rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1,d7d5 d2d4,1800,70,80,2000,middlegame mate mateIn2,https://lichess.org/test5,Reti_Opening")
    writer.close()
  }

  override def afterAll(): Unit = {
    if (sparkSession != null) sparkSession.stop()
    // Clean up temp files
    if (tempDir != null) deleteRecursive(tempDir)
    super.afterAll()
  }

  private def deleteRecursive(f: File): Unit = {
    if (f.isDirectory) f.listFiles().foreach(deleteRecursive)
    f.delete()
  }

  // ── Tests ───────────────────────────────────────────────────────────────────

  test("puzzleSchema should match CSV header") {
    val df = sparkSession.read
      .option("header", "true")
      .schema(ChessAnalyticsBatch.puzzleSchema)
      .csv(csvFile.getAbsolutePath)

    df.columns should contain allOf (
      "PuzzleId", "FEN", "Moves", "Rating",
      "RatingDeviation", "Popularity", "NbPlays",
      "Themes", "GameUrl", "OpeningTags"
    )
    df.count() shouldBe 5
  }

  test("rating distribution should group puzzles into correct buckets") {
    import org.apache.spark.sql.functions._

    val ss = sparkSession
    import ss.implicits._

    val df = sparkSession.read
      .option("header", "true")
      .schema(ChessAnalyticsBatch.puzzleSchema)
      .csv(csvFile.getAbsolutePath)

    val ratingDist = df
      .withColumn("RatingBucket", (floor(col("Rating") / 200) * 200).cast(IntegerType))
      .groupBy("RatingBucket")
      .count()
      .orderBy("RatingBucket")
      .as[(Int, Long)]
      .collect()
      .toMap

    // 800 → bucket 800, 1200 → 1200, 1500 → 1400, 1800 → 1800, 2200 → 2200
    ratingDist(800)  shouldBe 1
    ratingDist(1200) shouldBe 1
    ratingDist(1400) shouldBe 1
    ratingDist(1800) shouldBe 1
    ratingDist(2200) shouldBe 1
  }

  test("theme analysis should count themes correctly") {
    import org.apache.spark.sql.functions._

    val ss = sparkSession
    import ss.implicits._

    val df = sparkSession.read
      .option("header", "true")
      .schema(ChessAnalyticsBatch.puzzleSchema)
      .csv(csvFile.getAbsolutePath)

    val themes = df
      .filter(col("Themes").isNotNull && col("Themes") =!= "")
      .withColumn("Theme", explode(split(col("Themes"), " ")))
      .groupBy("Theme")
      .count()
      .as[(String, Long)]
      .collect()
      .toMap

    // "short" appears in puzzles 00001 and 00003
    themes("short") shouldBe 2
    // "middlegame" appears in puzzles 00002 and 00005
    themes("middlegame") shouldBe 2
    // "mateIn2" appears only in puzzle 00005
    themes("mateIn2") shouldBe 1
  }

  test("difficulty categories should be assigned correctly") {
    import org.apache.spark.sql.functions._

    val ss = sparkSession
    import ss.implicits._

    val df = sparkSession.read
      .option("header", "true")
      .schema(ChessAnalyticsBatch.puzzleSchema)
      .csv(csvFile.getAbsolutePath)

    val difficulties = df
      .withColumn("Difficulty", when(col("Rating") < 1200, "Beginner")
        .when(col("Rating") < 1600, "Intermediate")
        .when(col("Rating") < 2000, "Advanced")
        .when(col("Rating") < 2400, "Expert")
        .otherwise("Master"))
      .groupBy("Difficulty")
      .count()
      .as[(String, Long)]
      .collect()
      .toMap

    difficulties("Beginner")     shouldBe 1   // 800
    difficulties("Intermediate") shouldBe 2   // 1200, 1500
    difficulties("Advanced")     shouldBe 1   // 1800
    difficulties("Expert")       shouldBe 1   // 2200
  }

  test("opening tag analysis should handle null and empty opening tags") {
    import org.apache.spark.sql.functions._

    val ss = sparkSession
    import ss.implicits._

    val df = sparkSession.read
      .option("header", "true")
      .schema(ChessAnalyticsBatch.puzzleSchema)
      .csv(csvFile.getAbsolutePath)

    val openings = df
      .filter(col("OpeningTags").isNotNull && col("OpeningTags") =!= "")
      .withColumn("Opening", explode(split(col("OpeningTags"), " ")))
      .groupBy("Opening")
      .count()
      .as[(String, Long)]
      .collect()
      .toMap

    // Kings_Pawn_Game appears in 2 puzzles
    openings("Kings_Pawn_Game") shouldBe 2
    openings("Queens_Gambit")   shouldBe 1
    openings("Reti_Opening")    shouldBe 1
    // Puzzle 00004 has empty OpeningTags – should be excluded
    openings.size shouldBe 3
  }

  test("SparkConfig should load defaults from application.conf") {
    val config = SparkConfig.load()
    config.dbDriver shouldBe "org.postgresql.Driver"
    config.topicGameReports shouldBe "chess-game-reports"
    config.topicMoves shouldBe "chess-moves"
    config.topicEvaluations shouldBe "chess-evaluations"
    config.analyticsTable shouldBe "spark_analytics"
    config.enablePuzzle shouldBe false
  }
}
