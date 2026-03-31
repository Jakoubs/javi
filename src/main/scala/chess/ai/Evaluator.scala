package chess.ai

import chess.model.*
import java.io.{File, PrintWriter}
import scala.io.Source
import scala.util.Try
import java.util.concurrent.atomic.AtomicReference

/**
 * Evaluation function for board states.
 * Uses material weights and Piece-Square Tables (PSTs).
 * Weights are thread-safe via AtomicReference and can be updated for learning.
 */
object Evaluator:

  // --- Thread-safe Material Weights ---
  // AtomicReference[Double] allows concurrent compare-and-swap updates
  private val pawnWeight   = AtomicReference(100.0)
  private val knightWeight = AtomicReference(320.0)
  private val bishopWeight = AtomicReference(330.0)
  private val rookWeight   = AtomicReference(500.0)
  private val queenWeight  = AtomicReference(900.0)

  private val weightsFile = "ai_weights.txt"

  // Thread-safe atomic add: loops until CAS succeeds
  private def atomicAdd(ref: AtomicReference[Double], delta: Double): Unit =
    var updated = false
    while !updated do
      val current = ref.get()
      updated = ref.compareAndSet(current, current + delta)

  /**
   * Evaluate the board from White's perspective.
   * Positive = White advantage, Negative = Black advantage.
   */
  def evaluate(state: GameState): Double =
    val board = state.board
    var score = 0.0

    for (pos, piece) <- board.pieces do
      val material = weightOf(piece.pieceType)
      val pstBonus = pstScore(piece, pos)
      
      if piece.color == Color.White then
        score += material + pstBonus
      else
        score -= material + pstBonus

    score

  private def weightOf(pt: PieceType): Double = pt match
    case PieceType.Pawn   => pawnWeight.get()
    case PieceType.Knight => knightWeight.get()
    case PieceType.Bishop => bishopWeight.get()
    case PieceType.Rook   => rookWeight.get()
    case PieceType.Queen  => queenWeight.get()
    case PieceType.King   => 20000.0
  
  /** Piece-Square Tables — reads from trainable pstWeights */
  private def pstScore(piece: Piece, pos: Pos): Double =
    val (c, r) = (pos.col, pos.row)
    val (relC, relR) = if piece.color == Color.White then (c, r) else (c, 7 - r)
    val index = relR * 8 + relC
    pstWeights(piece.pieceType)(index).get()

  // --- PST Tables (Simplified) ---
  private val pawnPst = Array(
    Array(0,  0,  0,  0,  0,  0,  0,  0),
    Array(50, 50, 50, 50, 50, 50, 50, 50),
    Array(10, 10, 20, 30, 30, 20, 10, 10),
    Array(5,  5, 10, 25, 25, 10,  5,  5),
    Array(0,  0,  0, 20, 20,  0,  0,  0),
    Array(5, -5,-10,  0,  0,-10, -5,  5),
    Array(5, 10, 10,-20,-20, 10, 10,  5),
    Array(0,  0,  0,  0,  0,  0,  0,  0)
  )

  private val knightPst = Array(
    Array(-50,-40,-30,-30,-30,-30,-40,-50),
    Array(-40,-20,  0,  0,  0,  0,-20,-40),
    Array(-30,  0, 10, 15, 15, 10,  0,-30),
    Array(-30,  5, 15, 20, 20, 15,  5,-30),
    Array(-30,  0, 15, 20, 20, 15,  0,-30),
    Array(-30,  5, 10, 15, 15, 10,  5,-30),
    Array(-40,-20,  0,  5,  5,  0,-20,-40),
    Array(-50,-40,-30,-30,-30,-30,-40,-50)
  )

  private val bishopPst = Array(
    Array(-20,-10,-10,-10,-10,-10,-10,-20),
    Array(-10,  0,  0,  0,  0,  0,  0,-10),
    Array(-10,  0,  5, 10, 10,  5,  0,-10),
    Array(-10,  5,  5, 10, 10,  5,  5,-10),
    Array(-10,  0, 10, 10, 10, 10,  0,-10),
    Array(-10, 10, 10, 10, 10, 10, 10,-10),
    Array(-10,  5,  0,  0,  0,  0,  5,-10),
    Array(-20,-10,-10,-10,-10,-10,-10,-20)
  )

  private val rookPst = Array(
    Array( 0,  0,  0,  0,  0,  0,  0,  0),
    Array( 5, 10, 10, 10, 10, 10, 10,  5),
    Array(-5,  0,  0,  0,  0,  0,  0, -5),
    Array(-5,  0,  0,  0,  0,  0,  0, -5),
    Array(-5,  0,  0,  0,  0,  0,  0, -5),
    Array(-5,  0,  0,  0,  0,  0,  0, -5),
    Array(-5,  0,  0,  0,  0,  0,  0, -5),
    Array( 0,  0,  0,  5,  5,  0,  0,  0)
  )

  private val queenPst = Array(
    Array(-20,-10,-10, -5, -5,-10,-10,-20),
    Array(-10,  0,  0,  0,  0,  0,  0,-10),
    Array(-10,  0,  5,  5,  5,  5,  0,-10),
    Array( -5,  0,  5,  5,  5,  5,  0, -5),
    Array(  0,  0,  5,  5,  5,  5,  0, -5),
    Array(-10,  5,  5,  5,  5,  5,  0,-10),
    Array(-10,  0,  5,  0,  0,  0,  0,-10),
    Array(-20,-10,-10, -5, -5,-10,-10,-20)
  )

  private val kingPst = Array(
    Array(-30,-40,-40,-50,-50,-40,-40,-30),
    Array(-30,-40,-40,-50,-50,-40,-40,-30),
    Array(-30,-40,-40,-50,-50,-40,-40,-30),
    Array(-30,-40,-40,-50,-50,-40,-40,-30),
    Array(-20,-30,-30,-40,-40,-30,-30,-20),
    Array(-10,-20,-20,-20,-20,-20,-20,-10),
    Array( 20, 20,  0,  0,  0,  0, 20, 20),
    Array( 20, 30, 10,  0,  0, 10, 30, 20)
  )

  // --- Trainable PST weights (initialized from hardcoded tables above) ---
  // IMPORTANT: This MUST be defined AFTER the PST arrays above!
  private val pstWeights: Map[PieceType, Array[AtomicReference[Double]]] = {
    val tables: Array[(PieceType, Array[Array[Int]])] = Array(
      (PieceType.Pawn,   pawnPst),
      (PieceType.Knight, knightPst),
      (PieceType.Bishop, bishopPst),
      (PieceType.Rook,   rookPst),
      (PieceType.Queen,  queenPst),
      (PieceType.King,   kingPst)
    )
    tables.map { case (pt, table) =>
      val flat = new Array[AtomicReference[Double]](64)
      for (r <- 0 until 8; c <- 0 until 8) do
        flat(r * 8 + c) = AtomicReference(table(r)(c).toDouble)
      pt -> flat
    }.toMap
  }

  // --- Persistence & Learning ---
  
  /**
   * Load weights from a file. 
   * @param path Optional file path; defaults to "ai_weights.txt"
   */
  def loadWeights(path: String = weightsFile): Unit =
    Try {
      val source = Source.fromFile(path)
      val lines = source.getLines().toList
      source.close()
      if lines.size >= 5 then
        pawnWeight.set(lines(0).toDouble)
        knightWeight.set(lines(1).toDouble)
        bishopWeight.set(lines(2).toDouble)
        rookWeight.set(lines(3).toDouble)
        queenWeight.set(lines(4).toDouble)
      // Load PSTs (lines 5..388)
      if lines.size >= 389 then
        var idx = 5
        val pieceOrder = Array(PieceType.Pawn, PieceType.Knight, PieceType.Bishop, PieceType.Rook, PieceType.Queen, PieceType.King)
        for pt <- pieceOrder do
          val arr = pstWeights(pt)
          for i <- 0 until 64 do
            arr(i).set(lines(idx).toDouble)
            idx += 1
    }

  /**
   * Save current weights to a file (5 material + 384 PST = 389 total).
   */
  def saveWeights(path: String = weightsFile): Unit = synchronized {
    val out = new PrintWriter(new File(path))
    out.println(pawnWeight.get())
    out.println(knightWeight.get())
    out.println(bishopWeight.get())
    out.println(rookWeight.get())
    out.println(queenWeight.get())
    val pieceOrder = Array(PieceType.Pawn, PieceType.Knight, PieceType.Bishop, PieceType.Rook, PieceType.Queen, PieceType.King)
    for pt <- pieceOrder do
      val arr = pstWeights(pt)
      for i <- 0 until 64 do
        out.println(arr(i).get())
    out.close()
  }

  // Thread-safe weight update — material + optional positional
  def updateWeights(delta: Double, pieceType: PieceType, color: Color, pos: Option[Pos] = None): Unit =
    // Material update
    pieceType match
      case PieceType.Pawn   => atomicAdd(pawnWeight,   delta)
      case PieceType.Knight => atomicAdd(knightWeight, delta)
      case PieceType.Bishop => atomicAdd(bishopWeight, delta)
      case PieceType.Rook   => atomicAdd(rookWeight,   delta)
      case PieceType.Queen  => atomicAdd(queenWeight,  delta)
      case _ => ()
    // PST update
    pos.foreach { p =>
      val relR = if color == Color.White then p.row else 7 - p.row
      val index = relR * 8 + p.col
      atomicAdd(pstWeights(pieceType)(index), delta * 0.1) // smaller LR for positional
    }
