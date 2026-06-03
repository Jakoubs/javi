package chess.benchmark

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import chess.model._

/**
 * JMH Benchmarks for hot chess functions.
 *
 * Run with:
 *   sbt "benchmark/Jmh/run -i 5 -wi 3 -f 1 -t 1"
 *
 * Benchmarked functions:
 *   - Board.toFenPlacement (serialization – called in every state response, threefold-repetition, PGN)
 *   - GameState.fromFen    (parsing – called for puzzle endpoints)
 *   - MoveGenerator.legalMoves (move generation – called on every command)
 */
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class FenBenchmark:

  // ── Test data ──────────────────────────────────────────────────────────────

  val initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  val midgameFen = "r1bqkb1r/pppppppp/2n2n2/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
  val complexFen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1" // Position 2 (Kiwipete)

  var initialBoard: Board = _
  var midgameBoard: Board = _
  var complexBoard: Board = _
  var initialState: GameState = _
  var midgameState: GameState = _
  var complexState: GameState = _

  @Setup(Level.Trial)
  def setup(): Unit =
    initialBoard = Board.initial
    midgameBoard = Board.fromFenPlacement(midgameFen.split(" ")(0)).toOption.get
    complexBoard = Board.fromFenPlacement(complexFen.split(" ")(0)).toOption.get
    initialState = GameState.initial
    midgameState = GameState.fromFen(midgameFen).toOption.get
    complexState = GameState.fromFen(complexFen).toOption.get

  // ── Board.toFenPlacement benchmarks ────────────────────────────────────────

  @Benchmark
  def toFenPlacement_initial(): String =
    initialBoard.toFenPlacement

  @Benchmark
  def toFenPlacement_midgame(): String =
    midgameBoard.toFenPlacement

  @Benchmark
  def toFenPlacement_complex(): String =
    complexBoard.toFenPlacement

  // ── GameState.fromFen benchmarks ───────────────────────────────────────────

  @Benchmark
  def fromFen_initial(): Either[String, GameState] =
    GameState.fromFen(initialFen)

  @Benchmark
  def fromFen_midgame(): Either[String, GameState] =
    GameState.fromFen(midgameFen)

  @Benchmark
  def fromFen_complex(): Either[String, GameState] =
    GameState.fromFen(complexFen)

  // ── MoveGenerator.legalMoves benchmarks ────────────────────────────────────

  @Benchmark
  def legalMoves_initial(): List[Move] =
    MoveGenerator.legalMoves(initialState)

  @Benchmark
  def legalMoves_midgame(): List[Move] =
    MoveGenerator.legalMoves(midgameState)

  @Benchmark
  def legalMoves_complex(): List[Move] =
    MoveGenerator.legalMoves(complexState)
