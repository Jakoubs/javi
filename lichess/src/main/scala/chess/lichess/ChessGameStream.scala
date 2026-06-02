package chess.lichess

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.*
import org.apache.pekko.stream.{KillSwitches, UniqueKillSwitch}
import chess.ai.{AlphaBetaAgent, Evaluator}
import chess.model.*

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random

// ─── Domain types flowing through the stream ─────────────────────────────────

/** A single position snapshot captured mid-game. */
case class PositionSnapshot(
  gameId:      Int,
  moveNumber:  Int,
  activeColor: Color,
  state:       GameState,
  evalScore:   Double,        // from White's perspective (Evaluator.evaluate)
  legalMoves:  Int,
  isCheck:     Boolean
)

/** Aggregated statistics for a completed game. */
case class GameReport(
  gameId:       Int,
  totalMoves:   Int,
  result:       GameStatus,
  maxEval:      Double,       // highest eval reached (White advantage)
  minEval:      Double,       // lowest eval reached  (Black advantage)
  avgLegal:     Double,       // average branching factor
  peakMobility: Int           // maximum number of legal moves seen at once
)

// ─── Stream graph ─────────────────────────────────────────────────────────────

object ChessGameStream:

  /**
   * Run `numGames` self-play games through a Pekko Stream pipeline and print a
   * summary report to stdout.
   *
   * Pipeline shape:
   *
   *   Source[RawGame]
   *     ──▶ Flow[PositionSnapshot]    (analyse every position)
   *     ──▶ Flow[GameReport]          (aggregate per game)
   *     ──▶ Sink (collect & print)
   */
  def run(numGames: Int = 10)(using system: ActorSystem[?]): Future[Unit] =
    given ExecutionContext = system.executionContext

    println(s"\n╔══════════════════════════════════════════════╗")
    println(s"║   Chess Game Stream  –  $numGames games          ║")
    println(s"╚══════════════════════════════════════════════╝\n")

    // ── SOURCE ────────────────────────────────────────────────────────────────
    //
    // Produces one RawGame (= list of GameState snapshots) per emitted element.
    // We use Source.fromIterator so the heavy CPU work happens downstream and
    // is naturally back-pressured by the sink.

    val source: Source[List[PositionSnapshot], _] =
      Source.fromIterator(() => Iterator.range(0, numGames))
        .map(gameId => playRandomGame(gameId))

    // ── FLOW 1 – Position Analysis ────────────────────────────────────────────
    //
    // Flattens the per-game list into individual PositionSnapshot elements.
    // mapConcat is the idiomatic Pekko/Akka way to do a flatMap on a Source.

    val analysisFlow: Flow[List[PositionSnapshot], PositionSnapshot, _] =
      Flow[List[PositionSnapshot]]
        .mapConcat(identity)        // flatten List[Snapshot] → Snapshot

    // ── FLOW 2 – Per-game aggregation ─────────────────────────────────────────
    //
    // Groups snapshots by gameId and folds them into a GameReport.
    // groupBy opens a sub-stream per gameId; fold accumulates the stats;
    // mergeSubstreams re-joins everything back into a single stream.

    val aggregationFlow: Flow[PositionSnapshot, GameReport, _] =
      Flow[PositionSnapshot]
        .groupBy(maxSubstreams = numGames + 1, snap => snap.gameId)
        .fold(GameReportAccumulator.empty) { (acc, snap) =>
          acc.update(snap)
        }
        .map(_.toReport)
        .mergeSubstreams

    // ── SINK ──────────────────────────────────────────────────────────────────
    //
    // Collect all GameReports in memory, then print a formatted summary table.

    val sink: Sink[GameReport, Future[Seq[GameReport]]] =
      Sink.seq[GameReport]

    // ── Wire & run ────────────────────────────────────────────────────────────

    source
      .via(analysisFlow)
      .via(aggregationFlow)
      .runWith(sink)
      .map { reports =>
        printSummary(reports.sortBy(_.gameId))
      }

  // ─── Random self-play ──────────────────────────────────────────────────────

  /**
   * Play a chess game by letting both sides pick a random legal move each turn.
   * Returns a list of PositionSnapshots (one per half-move) including the
   * terminal state.
   *
   * We use a simple random-move policy rather than the full AlphaBeta agent so
   * the stream stays snappy for demo purposes.  Swap in `AlphaBetaAgent` for
   * a stronger (but slower) self-play source.
   */
  private def playRandomGame(gameId: Int, maxMoves: Int = 150): List[PositionSnapshot] =
    val snapshots = scala.collection.mutable.ListBuffer.empty[PositionSnapshot]
    var state     = GameState.initial
    var finished  = false
    var moveNum   = 0

    while !finished && moveNum < maxMoves do
      val status    = GameRules.computeStatus(state)
      val legal     = MoveGenerator.legalMoves(state)
      val evalScore = Evaluator.evaluate(state)
      val isCheck   = status.isInstanceOf[GameStatus.Check]

      snapshots += PositionSnapshot(
        gameId      = gameId,
        moveNumber  = moveNum,
        activeColor = state.activeColor,
        state       = state,
        evalScore   = evalScore,
        legalMoves  = legal.size,
        isCheck     = isCheck
      )

      status match
        case GameStatus.Playing | GameStatus.Check(_) =>
          if legal.isEmpty then
            finished = true
          else
            val move = legal(Random.nextInt(legal.size))
            state    = GameRules.applyMove(state, move)
            moveNum += 1
        case _ =>
          finished = true

    // Capture terminal state
    val finalStatus = GameRules.computeStatus(state)
    snapshots += PositionSnapshot(
      gameId      = gameId,
      moveNumber  = moveNum,
      activeColor = state.activeColor,
      state       = state,
      evalScore   = Evaluator.evaluate(state),
      legalMoves  = 0,
      isCheck     = finalStatus.isInstanceOf[GameStatus.Check]
    )

    snapshots.toList

  // ─── Accumulator (fold state) ──────────────────────────────────────────────

  private case class GameReportAccumulator(
    gameId:       Int,
    totalMoves:   Int,
    lastStatus:   GameStatus,
    maxEval:      Double,
    minEval:      Double,
    evalSum:      Double,
    legalSum:     Int,
    peakMobility: Int,
    count:        Int
  ):
    def update(snap: PositionSnapshot): GameReportAccumulator =
      copy(
        gameId       = snap.gameId,
        totalMoves   = snap.moveNumber,
        lastStatus   = GameRules.computeStatus(snap.state),
        maxEval      = math.max(maxEval,  snap.evalScore),
        minEval      = math.min(minEval,  snap.evalScore),
        evalSum      = evalSum      + snap.evalScore,
        legalSum     = legalSum     + snap.legalMoves,
        peakMobility = math.max(peakMobility, snap.legalMoves),
        count        = count + 1
      )

    def toReport: GameReport =
      GameReport(
        gameId       = gameId,
        totalMoves   = totalMoves,
        result       = lastStatus,
        maxEval      = maxEval,
        minEval      = minEval,
        avgLegal     = if count > 0 then legalSum.toDouble / count else 0.0,
        peakMobility = peakMobility
      )

  private object GameReportAccumulator:
    val empty: GameReportAccumulator = GameReportAccumulator(
      gameId       = 0,
      totalMoves   = 0,
      lastStatus   = GameStatus.Playing,
      maxEval      = Double.MinValue,
      minEval      = Double.MaxValue,
      evalSum      = 0.0,
      legalSum     = 0,
      peakMobility = 0,
      count        = 0
    )

  // ─── Pretty-print summary ──────────────────────────────────────────────────

  private def resultLabel(s: GameStatus): String = s match
    case GameStatus.Checkmate(loser)  => if loser == Color.White then "Black wins ♟" else "White wins ♙"
    case GameStatus.Stalemate         => "Stalemate     "
    case GameStatus.Draw(reason)      => s"Draw ($reason)"
    case GameStatus.Resigned(loser)   => s"${loser} resigned"
    case GameStatus.Playing           => "Max moves hit "
    case GameStatus.Check(_)          => "Check?        "
    case GameStatus.Timeout(loser)    => s"${loser} timeout"

  private def bar(v: Double, range: Double, width: Int = 20): String =
    val ratio  = math.min(1.0, math.abs(v) / math.max(1.0, range))
    val filled = (ratio * width).toInt
    "█" * filled + "░" * (width - filled)

  private def printSummary(reports: Seq[GameReport]): Unit =
    val separator = "─" * 90
    println(separator)
    println(f"${"Game"}%6s  ${"Result"}%-22s  ${"Moves"}%5s  ${"MaxEval"}%8s  ${"MinEval"}%8s  ${"AvgLegal"}%8s  ${"PeakMob"}%7s")
    println(separator)

    val maxAbsEval = reports.map(r => math.max(math.abs(r.maxEval), math.abs(r.minEval))).maxOption.getOrElse(1.0)

    reports.foreach { r =>
      val resultStr = resultLabel(r.result)
      val evalBar   = bar(r.maxEval, maxAbsEval)
      println(
        f"#${r.gameId}%5d  ${resultStr}%-22s  ${r.totalMoves}%5d  ${r.maxEval}%+8.1f  ${r.minEval}%+8.1f  ${r.avgLegal}%8.1f  ${r.peakMobility}%7d"
      )
    }

    println(separator)

    // Aggregate stats across all games
    val results   = reports.map(_.result)
    val whiteWins = reports.count(r => r.result match
                      case GameStatus.Checkmate(Color.Black) => true
                      case _ => false)
    val blackWins    = reports.count(r => r.result match
                         case GameStatus.Checkmate(Color.White) => true
                         case _ => false)
    val draws        = reports.count(r => r.result match
                         case GameStatus.Stalemate | GameStatus.Draw(_) => true
                         case _ => false)
    val avgMoves     = reports.map(_.totalMoves).sum.toDouble / reports.size
    val avgMobility  = reports.map(_.avgLegal).sum / reports.size
    val avgMaxEval   = reports.map(r => math.abs(r.maxEval)).sum / reports.size

    println(s"\n📊  Summary across ${reports.size} games:")
    println(f"    White wins : $whiteWins%3d  (${whiteWins * 100.0 / reports.size}%5.1f%%)")
    println(f"    Black wins : $blackWins%3d  (${blackWins * 100.0 / reports.size}%5.1f%%)")
    println(f"    Draws      : $draws%3d  (${draws * 100.0 / reports.size}%5.1f%%)")
    println(f"    Avg moves  : $avgMoves%6.1f  half-moves per game")
    println(f"    Avg legal  : $avgMobility%6.1f  moves per position")
    println(f"    Avg |eval| : $avgMaxEval%6.1f  centipawns peak\n")

// ─── Standalone entry point ───────────────────────────────────────────────────

object ChessStreamMain:

  def main(args: Array[String]): Unit =
    val numGames = args.headOption.flatMap(_.toIntOption).getOrElse(10)

    given system: ActorSystem[Nothing] =
      ActorSystem(Behaviors.empty, "ChessStreamSystem")

    given ec: ExecutionContext = system.executionContext

    println(s"Starting Chess Game Stream with $numGames self-play games …")

    val done = ChessGameStream.run(numGames)

    done.onComplete { _ =>
      system.terminate()
    }

    Await.result(system.whenTerminated, 5.minutes)
    println("Stream finished. Goodbye!")
