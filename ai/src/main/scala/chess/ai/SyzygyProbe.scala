package chess.ai

import chess.model.GameState
import chess.model.Move
import chess.util.parser.{CoordinateMoveParser, SanMoveParser}

import java.io.IOException
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

object SyzygyProbe:

  final case class ProbeResult(
    move: Move,
    wdl: Int,
    dtz: Option[Int],
    dtm: Option[Int],
    provider: String
  )

  private val Enabled =
    sys.env.get("CHESS_SYZYGY_ENABLED").forall(_.trim.toLowerCase != "false")
  private val PathSetting =
    sys.env.get("CHESS_SYZYGY_PATH").filter(_.trim.nonEmpty).getOrElse("data/syzygy/3-4-5/3-4-5")
  private val ProbeCommand =
    sys.env.get("CHESS_SYZYGY_PROBE_CMD").filter(_.trim.nonEmpty).getOrElse("fathom")
  private val ProbeKind =
    sys.env.get("CHESS_SYZYGY_PROBE_KIND").map(_.trim.toLowerCase).getOrElse("fathom")
  private val MaxPieces =
    sys.env.get("CHESS_SYZYGY_MAX_PIECES").flatMap(_.toIntOption).getOrElse(5).max(2)
  private val TimeoutMs =
    sys.env.get("CHESS_SYZYGY_TIMEOUT_MS").flatMap(_.toLongOption).getOrElse(1500L).max(50L)

  private val cache = new ConcurrentHashMap[String, ProbeResult | Null]()
  private val unavailableLogged = new AtomicBoolean(false)

  def maxPieces: Int = MaxPieces

  def probe(state: GameState): Option[ProbeResult] =
    if !Enabled || state.board.pieceCount > MaxPieces then None
    else
      val fen = state.toFen
      Option(cache.get(fen)) match
        case some @ Some(_) => some
        case None =>
          val resolved = probeUncached(state)
          cache.put(fen, resolved.orNull)
          resolved

  private def probeUncached(state: GameState): Option[ProbeResult] =
    val tbPath = Paths.get(PathSetting)
    if !Files.isDirectory(tbPath) then
      logUnavailableOnce(s"directory_not_found path=$PathSetting")
      None
    else
      val command = buildCommand(tbPath, state.toFen)
      try
        val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
        val finished = process.waitFor(TimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if !finished then
          process.destroyForcibly()
          None
        else if process.exitValue() != 0 then
          None
        else
          val output = scala.io.Source.fromInputStream(process.getInputStream).mkString
          parseOutput(output, state)
      catch
        case _: IOException =>
          logUnavailableOnce(s"command_not_available cmd=$ProbeCommand")
          None

  private def buildCommand(path: Path, fen: String): List[String] =
    ProbeKind match
      case "probetool" =>
        List(ProbeCommand, "-u", "-p", path.toString, fen)
      case _ =>
        List(ProbeCommand, s"--path=${path.toString}", fen)

  private def parseOutput(output: String, state: GameState): Option[ProbeResult] =
    val wdl = parseWdl(output)
    val dtz = parseSignedInt(output, "(?im)^\\s*DTZ(?:50)?\\s*[:=]\\s*(-?\\d+)\\b")
      .orElse(parseSignedInt(output, "(?im)^\\s*DTZ50\\s*=\\s*(-?\\d+)\\b"))
    val dtm = parseSignedInt(output, "(?im)^\\s*DTM\\s*[:=]\\s*(-?\\d+)\\b")

    val preferredMoves =
      extractCandidateMoves(output, "WinningMoves")
        .orElse(extractCandidateMoves(output, "DrawingMoves"))
        .orElse(extractCandidateMoves(output, "LosingMoves"))
        .orElse(extractPrincipalVariationMove(output))
        .getOrElse(Nil)

    preferredMoves.iterator
      .flatMap(token => parseMove(token, state).iterator)
      .find(_ => true)
      .map(move => ProbeResult(move, wdl, dtz, dtm, s"syzygy-$ProbeKind"))

  private def extractCandidateMoves(output: String, label: String): Option[List[String]] =
    val regex = (s"(?im)^\\s*$label\\s*[:=]\\s*(.+)$$").r
    regex.findFirstMatchIn(output).map { m =>
      moveTokens(m.group(1))
    }.filter(_.nonEmpty)

  private def extractPrincipalVariationMove(output: String): Option[List[String]] =
    val pvStart = output.indexOf("1.")
    if pvStart < 0 then None
    else
      val line = output.substring(pvStart).linesIterator.nextOption().getOrElse("")
      val tokens = moveTokens(line)
      if tokens.nonEmpty then Some(tokens) else None

  private def moveTokens(text: String): List[String] =
    val coordinateRegex = "(?i)\\b[a-h][1-8][a-h][1-8][qrbn]?\\b".r
    val coordinate = coordinateRegex.findAllIn(text).map(_.trim).toList
    if coordinate.nonEmpty then coordinate
    else
      text
        .split("[,\\s]+")
        .iterator
        .map(_.trim)
        .filter(token => token.nonEmpty && !token.matches("\\d+\\.?"))
        .map(_.replaceAll("""^\d+\.(\.\.)?""", ""))
        .map(_.replaceAll("""[!?]+$""", ""))
        .map(_.replaceAll("""^\{.*\}$""", ""))
        .filter(_.nonEmpty)
        .toList

  private def parseMove(token: String, state: GameState): Option[Move] =
    CoordinateMoveParser.parse(token, state).toOption
      .orElse(SanMoveParser.parse(token, state).toOption)

  private def parseWdl(output: String): Int =
    val line =
      "(?im)^\\s*WDL\\s*[:=]\\s*([^\\r\\n]+)$".r.findFirstMatchIn(output).map(_.group(1).trim)
        .orElse("(?im)^\\s*WDL\\s*=\\s*([^\\r\\n]+)$".r.findFirstMatchIn(output).map(_.group(1).trim))
        .getOrElse("")
        .toLowerCase

    if line.contains("cursed") || line.matches(""".*\b1\b.*""") || line.contains("win") then 1
    else if line.contains("blessed") || line.matches(""".*\b-1\b.*""") || line.contains("loss") then -1
    else 0

  private def parseSignedInt(output: String, pattern: String): Option[Int] =
    pattern.r.findFirstMatchIn(output).flatMap(m => m.group(1).toIntOption)

  private def logUnavailableOnce(reason: String): Unit =
    if unavailableLogged.compareAndSet(false, true) then
      println(s"[AI][SYZYGY] unavailable reason=$reason")
