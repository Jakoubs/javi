package chess.ai

import chess.model.*
import java.io.{File, PrintWriter}
import scala.io.Source
import scala.util.Try

/**
 * Evaluation function for board states.
 * Uses material weights and Piece-Square Tables (PST).
 * Weights are persistent and can be updated for learning.
 */
object Evaluator:

  // --- Material Weights ---
  private var pawnWeight   = 100.0
  private var knightWeight = 320.0
  private var bishopWeight = 330.0
  private var rookWeight   = 500.0
  private var queenWeight  = 900.0

  private val weightsFile = "ai_weights.txt"

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
    case PieceType.Pawn   => pawnWeight
    case PieceType.Knight => knightWeight
    case PieceType.Bishop => bishopWeight
    case PieceType.Rook   => rookWeight
    case PieceType.Queen  => queenWeight
    case PieceType.King   => 20000.0
  
  /** Piece-Square Tables to encourage better positioning */
  private def pstScore(piece: Piece, pos: Pos): Double =
    val (c, r) = (pos.col, pos.row)
    val (relC, relR) = if piece.color == Color.White then (c, r) else (c, 7 - r)
    
    piece.pieceType match
      case PieceType.Pawn   => pawnPst(relR)(relC)
      case PieceType.Knight => knightPst(relR)(relC)
      case PieceType.Bishop => bishopPst(relR)(relC)
      case PieceType.Rook   => rookPst(relR)(relC)
      case PieceType.Queen  => queenPst(relR)(relC)
      case PieceType.King   => kingPst(relR)(relC)

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

  // --- Persistence & Learning ---

  def loadWeights(): Unit =
    Try {
      val source = Source.fromFile(weightsFile)
      val lines = source.getLines().toList
      source.close()
      if lines.size >= 5 then
        pawnWeight   = lines(0).toDouble
        knightWeight = lines(1).toDouble
        bishopWeight = lines(2).toDouble
        rookWeight   = lines(3).toDouble
        queenWeight  = lines(4).toDouble
    }

  def saveWeights(): Unit =
    val out = new PrintWriter(new File(weightsFile))
    out.println(pawnWeight)
    out.println(knightWeight)
    out.println(bishopWeight)
    out.println(rookWeight)
    out.println(queenWeight)
    out.close()

  def updateWeights(delta: Double, pieceType: PieceType): Unit =
    pieceType match
      case PieceType.Pawn   => pawnWeight   += delta
      case PieceType.Knight => knightWeight += delta
      case PieceType.Bishop => bishopWeight += delta
      case PieceType.Rook   => rookWeight   += delta
      case PieceType.Queen  => queenWeight  += delta
      case _ => ()
    saveWeights()
