package chess.rest

import chess.model.*
import chess.util.Pgn

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.sql.{Connection, DriverManager, PreparedStatement}
import java.util.zip.ZipInputStream
import scala.collection.mutable
import scala.util.control.NonFatal

object LichessEliteOpeningImportMain:

  private val DefaultHalfMoves = 16
  private val BatchSize = 5000
  private val Results = Set("1-0", "0-1", "1/2-1/2", "*")

  def main(args: Array[String]): Unit =
    val zipPath =
      args.headOption.filterNot(_.forall(_.isDigit))
        .orElse(sys.env.get("LICHESS_ELITE_ZIP"))
        .getOrElse {
          sys.error(
            "Usage: sbt \"rest/runMain chess.rest.LichessEliteOpeningImportMain <zip-path> [halfMoves]\" " +
              "or set LICHESS_ELITE_ZIP"
          )
        }
    val maxHalfMoves =
      numericArgs(args).headOption.flatMap(_.toIntOption).getOrElse(DefaultHalfMoves).max(1)
    val maxGames =
      numericArgs(args).lift(1)
        .flatMap(_.toIntOption)
        .orElse(sys.env.get("LICHESS_IMPORT_MAX_GAMES").flatMap(_.toIntOption))
        .getOrElse(Int.MaxValue)
        .max(1)

    val counts = mutable.HashMap.empty[String, mutable.HashMap[String, Int]]
    var games = 0
    var importedGames = 0
    var failedGames = 0

    println(s"[OPENING-IMPORT] source=$zipPath maxHalfMoves=$maxHalfMoves maxGames=$maxGames")

    try
      foreachGame(zipPath) { pgn =>
        if games >= maxGames then throw StopImport

        games += 1
        if importGame(pgn, maxHalfMoves, counts) then importedGames += 1
        else failedGames += 1

        if games % 10000 == 0 then
          val positions = counts.size
          val moves = counts.valuesIterator.map(_.size).sum
          println(s"[OPENING-IMPORT] games=$games imported=$importedGames failed=$failedGames positions=$positions moves=$moves")
      }
    catch
      case StopImport => println(s"[OPENING-IMPORT] reached maxGames=$maxGames")

    println(s"[OPENING-IMPORT] parsed games=$games imported=$importedGames failed=$failedGames")
    writeToDatabase(counts)
    println("[OPENING-IMPORT] done")

  private def foreachGame(zipPath: String)(consume: String => Unit): Unit =
    val zip = ZipInputStream(java.nio.file.Files.newInputStream(java.nio.file.Paths.get(zipPath)))
    try
      var entry = zip.getNextEntry()
      while entry != null do
        if !entry.isDirectory && entry.getName.toLowerCase.endsWith(".pgn") then
          println(s"[OPENING-IMPORT] reading entry=${entry.getName}")
          readGamesFromEntry(zip, consume)
        zip.closeEntry()
        entry = zip.getNextEntry()
    finally zip.close()

  private def readGamesFromEntry(zip: ZipInputStream, consume: String => Unit): Unit =
    val reader = BufferedReader(InputStreamReader(zip, StandardCharsets.UTF_8))
    val current = StringBuilder()
    var seenMoves = false
    var line = reader.readLine()

    while line != null do
      if line.startsWith("[Event ") && current.nonEmpty && seenMoves then
        consume(current.toString)
        current.clear()
        seenMoves = false

      current.append(line).append('\n')
      if line.nonEmpty && !line.startsWith("[") then seenMoves = true
      line = reader.readLine()

      if current.nonEmpty && seenMoves then consume(current.toString)

  private def numericArgs(args: Array[String]): Array[String] =
    args.filter(_.forall(_.isDigit))

  private object StopImport extends Throwable(null, null, false, false)

  private def importGame(
    pgn: String,
    maxHalfMoves: Int,
    counts: mutable.HashMap[String, mutable.HashMap[String, Int]]
  ): Boolean =
    try
      val tokens = extractSanTokens(pgn)
      var state = GameState.initial
      var ply = 0

      while ply < maxHalfMoves && ply < tokens.length do
        val token = tokens(ply)
        val legalMoves = MoveGenerator.legalMoves(state)
        val matchingMove = legalMoves.find { move =>
          val next = GameRules.applyMove(state, move)
          sanMatches(Pgn.toSan(state, move, next), token)
        }

        matchingMove match
          case Some(move) =>
            val fen = state.toFen
            val moveText = move.toInputString
            val byMove = counts.getOrElseUpdate(fen, mutable.HashMap.empty[String, Int])
            byMove.update(moveText, byMove.getOrElse(moveText, 0) + 1)
            state = GameRules.applyMove(state, move)
            ply += 1
          case None =>
            return ply > 0

      ply > 0
    catch
      case NonFatal(_) => false

  private def extractSanTokens(pgn: String): Array[String] =
    val noTags = pgn
      .linesIterator
      .filterNot(_.trim.startsWith("["))
      .mkString(" ")
    val stripped = stripCommentsAndVariations(noTags)
      .replaceAll("\\$\\d+", " ")
      .replaceAll("\\d+\\.(\\.\\.)?", " ")
      .replaceAll("\\s+", " ")
      .trim

    if stripped.isEmpty then Array.empty
    else
      stripped
        .split(" ")
        .iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .filterNot(Results.contains)
        .map(_.replaceAll("[!?]+$", ""))
        .filter(_.nonEmpty)
        .toArray

  private def stripCommentsAndVariations(text: String): String =
    val out = StringBuilder()
    var braceDepth = 0
    var parenDepth = 0

    text.foreach { ch =>
      ch match
        case '{' => braceDepth += 1
        case '}' => if braceDepth > 0 then braceDepth -= 1
        case '(' if braceDepth == 0 => parenDepth += 1
        case ')' if braceDepth == 0 => if parenDepth > 0 then parenDepth -= 1
        case _ if braceDepth == 0 && parenDepth == 0 => out.append(ch)
        case _ => ()
    }

    out.toString

  private def sanMatches(expected: String, actual: String): Boolean =
    normalizeSan(expected) == normalizeSan(actual)

  private def normalizeSan(san: String): String =
    san
      .replace("0-0-0", "O-O-O")
      .replace("0-0", "O-O")
      .replace("+", "")
      .replace("#", "")
      .replace("e.p.", "")
      .trim

  private def writeToDatabase(counts: mutable.HashMap[String, mutable.HashMap[String, Int]]): Unit =
    val url = sys.env.getOrElse("CHESS_DB_URL", "jdbc:postgresql://localhost:5433/chess")
    val user = sys.env.getOrElse("CHESS_DB_USER", "chess")
    val password = sys.env.getOrElse("CHESS_DB_PASSWORD", "chess")

    Class.forName("org.postgresql.Driver")
    val connection = DriverManager.getConnection(url, user, password)
    try
      connection.setAutoCommit(false)
      ensureSchema(connection)
      clearOpeningBook(connection)
      insertOpenings(connection, counts)
      insertBestOpenings(connection, counts)
      connection.commit()
    catch
      case NonFatal(e) =>
        connection.rollback()
        throw e
    finally connection.close()

  private def ensureSchema(connection: Connection): Unit =
    val stmt = connection.createStatement()
    try
      stmt.executeUpdate(
        """CREATE TABLE IF NOT EXISTS openings (
          |  fen TEXT NOT NULL,
          |  move TEXT NOT NULL,
          |  name TEXT,
          |  weight INTEGER NOT NULL,
          |  PRIMARY KEY (fen, move)
          |)""".stripMargin
      )
      stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_openings_fen ON openings(fen)")
      stmt.executeUpdate(
        """CREATE TABLE IF NOT EXISTS opening_best (
          |  fen TEXT PRIMARY KEY,
          |  move TEXT NOT NULL,
          |  name TEXT,
          |  weight INTEGER NOT NULL
          |)""".stripMargin
      )
    finally stmt.close()

  private def clearOpeningBook(connection: Connection): Unit =
    val stmt = connection.createStatement()
    try
      stmt.executeUpdate("DELETE FROM opening_best")
      stmt.executeUpdate("DELETE FROM openings")
    finally stmt.close()

  private def insertOpenings(
    connection: Connection,
    counts: mutable.HashMap[String, mutable.HashMap[String, Int]]
  ): Unit =
    val sql =
      """INSERT INTO openings (fen, move, name, weight)
        |VALUES (?, ?, ?, ?)
        |ON CONFLICT (fen, move) DO UPDATE SET weight = EXCLUDED.weight, name = EXCLUDED.name""".stripMargin
    val ps = connection.prepareStatement(sql)
    try
      var pending = 0
      var written = 0L
      counts.foreach { case (fen, byMove) =>
        byMove.foreach { case (move, weight) =>
          ps.setString(1, fen)
          ps.setString(2, move)
          ps.setString(3, "lichess_elite_2020-08")
          ps.setInt(4, weight)
          ps.addBatch()
          pending += 1
          written += 1
          if pending >= BatchSize then
            ps.executeBatch()
            pending = 0
            println(s"[OPENING-IMPORT] openings written=$written")
        }
      }
      if pending > 0 then ps.executeBatch()
      println(s"[OPENING-IMPORT] openings written=$written")
    finally ps.close()

  private def insertBestOpenings(
    connection: Connection,
    counts: mutable.HashMap[String, mutable.HashMap[String, Int]]
  ): Unit =
    val sql =
      """INSERT INTO opening_best (fen, move, name, weight)
        |VALUES (?, ?, ?, ?)
        |ON CONFLICT (fen) DO UPDATE SET move = EXCLUDED.move, name = EXCLUDED.name, weight = EXCLUDED.weight""".stripMargin
    val ps = connection.prepareStatement(sql)
    try
      var pending = 0
      var written = 0L
      counts.foreach { case (fen, byMove) =>
        val (move, weight) = byMove.maxBy { case (move, weight) => (weight, ReverseLex(move)) }
        ps.setString(1, fen)
        ps.setString(2, move)
        ps.setString(3, "lichess_elite_2020-08")
        ps.setInt(4, weight)
        ps.addBatch()
        pending += 1
        written += 1
        if pending >= BatchSize then
          ps.executeBatch()
          pending = 0
          println(s"[OPENING-IMPORT] opening_best written=$written")
      }
      if pending > 0 then ps.executeBatch()
      println(s"[OPENING-IMPORT] opening_best written=$written")
    finally ps.close()

  private final case class ReverseLex(value: String) extends Ordered[ReverseLex]:
    override def compare(that: ReverseLex): Int = that.value.compareTo(value)
