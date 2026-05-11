package chess.rest

import chess.model.{GameRules, GameState}
import chess.persistence.config.PersistenceConfig
import chess.util.parser.SanMoveParser
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdIOException
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection

import java.io.{BufferedInputStream, BufferedReader, InputStreamReader, StringReader}
import java.net.URL
import java.sql.{Connection, DriverManager}
import scala.collection.mutable
import scala.util.Try

object LichessDbOpeningImportMain:

  /**
   * Usage:
   * sbt "rest/runMain chess.rest.LichessDbOpeningImportMain 2026-01 [maxPly] [maxGames]"
   */
  def main(args: Array[String]): Unit =
    val month = args.headOption.getOrElse {
      println("Missing month argument. Example: 2026-01")
      sys.exit(1)
    }
    val maxPly = args.lift(1).flatMap(s => Try(s.toInt).toOption).getOrElse(20)
    val maxGames = args.lift(2).flatMap(s => Try(s.toLong).toOption).getOrElse(0L)

    val url = s"https://database.lichess.org/standard/lichess_db_standard_rated_${month}.pgn.zst"
    println(s"Opening import start | month=$month | maxPly=$maxPly | maxGames=$maxGames")
    println(s"Source: $url")

    val cfg = PersistenceConfig.load()
    val db = BulkOpeningImporter.connect(cfg)
    db.ensureSchema()

    val counts = mutable.HashMap.empty[(String, String), Int]
    var parsedGames = 0L
    var skippedGames = 0L
    val flushEveryGames = 5000L
    var totalUpserts = 0L

    val in = new BufferedInputStream(URL(url).openStream())
    val zstd = new ZstdInputStream(in)
    val reader = new BufferedReader(new InputStreamReader(zstd, "UTF-8"), 1 << 16)

    try
      var line: String = null
      val movetext = new StringBuilder(1024)

      var stop = false
      while (!stop && { line = safeReadLine(reader); line != null }) do
        if line.startsWith("[") then
          // Some PGN dumps may start the next game header directly.
          if movetext.nonEmpty then
            val ok = processGame(movetext.toString(), maxPly, counts)
            if ok then parsedGames += 1 else skippedGames += 1
            if parsedGames % 1000 == 0 then
              println(s"Progress: games=$parsedGames skipped=$skippedGames uniqueEntries=${counts.size}")
            if parsedGames > 0 && parsedGames % flushEveryGames == 0 then
              totalUpserts += db.flushCounts(counts)
              println(s"Checkpoint flush: games=$parsedGames totalUpserts=$totalUpserts")
            movetext.clear()
            if maxGames > 0 && parsedGames >= maxGames then
              println(s"Reached maxGames=$maxGames")
              stop = true
        else if line.trim.isEmpty then
          if movetext.nonEmpty then
            val ok = processGame(movetext.toString(), maxPly, counts)
            if ok then parsedGames += 1 else skippedGames += 1
            if parsedGames % 1000 == 0 then
              println(s"Progress: games=$parsedGames skipped=$skippedGames uniqueEntries=${counts.size}")
            if parsedGames > 0 && parsedGames % flushEveryGames == 0 then
              totalUpserts += db.flushCounts(counts)
              println(s"Checkpoint flush: games=$parsedGames totalUpserts=$totalUpserts")
            movetext.clear()
            if maxGames > 0 && parsedGames >= maxGames then
              println(s"Reached maxGames=$maxGames")
              stop = true
          ()
        else
          movetext.append(line).append(' ')

      println(s"Parsing finished: games=$parsedGames skipped=$skippedGames uniqueEntries=${counts.size}")

      totalUpserts += db.flushCounts(counts)
      db.rebuildOpeningBest()

      val total = db.countOpenings()
      val totalBest = db.countBest()
      println(s"Import complete. upserts=$totalUpserts totalOpenings=$total openingBestRows=$totalBest")

    finally
      reader.close()
      zstd.close()
      in.close()
      db.close()

  private def processGame(movetextRaw: String, maxPly: Int, counts: mutable.HashMap[(String, String), Int]): Boolean =
    val sanitized = stripPgnNoise(movetextRaw)
    val tokens = sanitized.split("\\s+").iterator
      .map(cleanToken)
      .filter(tok => tok.nonEmpty && !tok.contains(".") && !isResultToken(tok))
      .toList

    if tokens.isEmpty then return false

    var state: GameState = GameState.initial
    var ply = 0

    try
      tokens.foreach { san =>
        if ply < maxPly then
          val move = SanMoveParser.parse(san, state).get
          val key = (state.toFen, move.toInputString)
          counts.updateWith(key) {
            case Some(v) => Some(v + 1)
            case None => Some(1)
          }
          state = GameRules.applyMove(state, move)
          ply += 1
      }
      true
    catch
      case _: Throwable => false

  private def stripPgnNoise(s: String): String =
    // Remove comments and common annotation noise quickly.
    s
      .replaceAll("\\{[^}]*\\}", " ")
      .replaceAll("\\$[0-9]+", " ")
      .replaceAll("\\([^)]*\\)", " ")

  private def cleanToken(tok: String): String =
    tok.trim
      .replaceAll("[!?+#]+", "")
      .replaceAll("\\u0000", "")

  private def isResultToken(tok: String): Boolean =
    tok == "1-0" || tok == "0-1" || tok == "1/2-1/2" || tok == "*"

  private def safeReadLine(reader: BufferedReader): String =
    try reader.readLine()
    catch
      case e: ZstdIOException if e.getMessage != null && e.getMessage.toLowerCase.contains("truncated") =>
        println(s"Warning: truncated zstd stream encountered. Finishing with parsed subset. (${e.getMessage})")
        null

  private final class BulkOpeningImporter(private val conn: Connection):
    conn.setAutoCommit(false)

    private val copyManager = CopyManager(conn.unwrap(classOf[BaseConnection]))
    private val copySql = "COPY opening_import_stage (fen, move, weight) FROM STDIN WITH (FORMAT text)"
    private val createStageSql =
      """CREATE TEMP TABLE IF NOT EXISTS opening_import_stage (
        |  fen text NOT NULL,
        |  move text NOT NULL,
        |  weight integer NOT NULL
        |) ON COMMIT PRESERVE ROWS
        |""".stripMargin
    private val mergeSql =
      """INSERT INTO openings (fen, move, name, weight)
        |SELECT fen, move, NULL, SUM(weight)
        |FROM opening_import_stage
        |GROUP BY fen, move
        |ON CONFLICT (fen, move)
        |DO UPDATE SET weight = openings.weight + EXCLUDED.weight
        |""".stripMargin
    private val truncateStageSql = "TRUNCATE opening_import_stage"

    def ensureSchema(): Unit =
      val stmt = conn.createStatement()
      try
        stmt.execute(
          """CREATE TABLE IF NOT EXISTS openings (
            |  fen text NOT NULL,
            |  move text NOT NULL,
            |  name text NULL,
            |  weight integer NOT NULL,
            |  CONSTRAINT pk_openings PRIMARY KEY (fen, move)
            |)
            |""".stripMargin
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_openings_fen ON openings (fen)")
        stmt.execute(
          """CREATE TABLE IF NOT EXISTS opening_best (
            |  fen text PRIMARY KEY,
            |  move text NOT NULL,
            |  name text NULL,
            |  weight integer NOT NULL
            |)
            |""".stripMargin
        )
        stmt.execute(createStageSql)
        conn.commit()
      finally stmt.close()

    def flushCounts(counts: mutable.HashMap[(String, String), Int]): Long =
      if counts.isEmpty then return 0L

      val tsv = new StringBuilder(counts.size * 80)
      counts.foreach { case ((fen, move), weight) =>
        tsv
          .append(escapeCopyText(fen)).append('\t')
          .append(escapeCopyText(move)).append('\t')
          .append(weight).append('\n')
      }

      val stmt = conn.createStatement()
      try
        stmt.execute(truncateStageSql)
        copyManager.copyIn(copySql, StringReader(tsv.toString()))
        val upserts = stmt.executeUpdate(mergeSql).toLong
        conn.commit()
        counts.clear()
        upserts
      catch
        case e: Throwable =>
          conn.rollback()
          throw e
      finally stmt.close()

    def rebuildOpeningBest(): Unit =
      val stmt = conn.createStatement()
      try
        stmt.execute("TRUNCATE opening_best")
        stmt.executeUpdate(
          """INSERT INTO opening_best (fen, move, name, weight)
            |SELECT DISTINCT ON (fen) fen, move, name, weight
            |FROM openings
            |ORDER BY fen, weight DESC, move ASC
            |""".stripMargin
        )
        conn.commit()
      catch
        case e: Throwable =>
          conn.rollback()
          throw e
      finally stmt.close()

    def countOpenings(): Long =
      val stmt = conn.createStatement()
      try
        val rs = stmt.executeQuery("SELECT COUNT(*) FROM openings")
        rs.next()
        rs.getLong(1)
      finally stmt.close()

    def countBest(): Long =
      val stmt = conn.createStatement()
      try
        val rs = stmt.executeQuery("SELECT COUNT(*) FROM opening_best")
        rs.next()
        rs.getLong(1)
      finally stmt.close()

    def close(): Unit = conn.close()

    private def escapeCopyText(value: String): String =
      value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

  private object BulkOpeningImporter:
    def connect(cfg: PersistenceConfig): BulkOpeningImporter =
      Class.forName(cfg.slick.driver)
      new BulkOpeningImporter(
        DriverManager.getConnection(cfg.slick.url, cfg.slick.user, cfg.slick.password)
      )

