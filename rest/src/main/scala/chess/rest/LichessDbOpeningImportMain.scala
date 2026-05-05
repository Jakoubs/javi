package chess.rest

import cats.effect.unsafe.implicits.global
import chess.model.{GameRules, GameState}
import chess.persistence.PersistenceModule
import chess.persistence.model.Opening
import chess.util.parser.SanMoveParser
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdIOException

import java.io.{BufferedInputStream, BufferedReader, InputStreamReader}
import java.net.URL
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

    val persistence = PersistenceModule.build().unsafeRunSync()
    val dao = persistence.openingDao

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
              totalUpserts += flushCounts(dao, counts)
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
              totalUpserts += flushCounts(dao, counts)
              println(s"Checkpoint flush: games=$parsedGames totalUpserts=$totalUpserts")
            movetext.clear()
            if maxGames > 0 && parsedGames >= maxGames then
              println(s"Reached maxGames=$maxGames")
              stop = true
          ()
        else
          movetext.append(line).append(' ')

      println(s"Parsing finished: games=$parsedGames skipped=$skippedGames uniqueEntries=${counts.size}")

      totalUpserts += flushCounts(dao, counts)

      val total = dao.count().unsafeRunSync()
      println(s"Import complete. upserts=$totalUpserts totalOpenings=$total")

    finally
      reader.close()
      zstd.close()
      in.close()
      persistence.close().unsafeRunSync()

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

  private def flushCounts(dao: chess.persistence.dao.OpeningDao, counts: mutable.HashMap[(String, String), Int]): Long =
    if counts.isEmpty then return 0L

    val groupedByFen = counts.iterator.toSeq.groupBy(_._1._1)
    var upserts = 0L
    groupedByFen.foreach { case (fen, entries) =>
      val existing = dao.findByFen(fen).unsafeRunSync().map(o => o.move -> o).toMap
      entries.foreach { case ((_, move), inc) =>
        val old = existing.get(move).map(_.weight).getOrElse(0)
        val merged = Opening(fen = fen, move = move, name = None, weight = old + inc)
        dao.save(merged).unsafeRunSync()
        upserts += 1
      }
    }
    counts.clear()
    upserts

  private def safeReadLine(reader: BufferedReader): String =
    try reader.readLine()
    catch
      case e: ZstdIOException if e.getMessage != null && e.getMessage.toLowerCase.contains("truncated") =>
        println(s"Warning: truncated zstd stream encountered. Finishing with parsed subset. (${e.getMessage})")
        null

