package chess.util.parser

import chess.model.{GameState, Move}
import scala.util.Try

/**
 * Common trait for move parsing strategies (Coordinate vs SAN).
 */
trait MoveParser:
  def parse(input: String, state: GameState): Try[Move]

/**
 * Coordinate-based move parser (e.g., "e2e4", "e7e8q").
 */
object CoordinateMoveParser extends MoveParser:
  import chess.model.{Pos, PieceType}
  
  def parse(input: String, state: GameState): Try[Move] = Try {
    val cleaned = input.trim.toLowerCase
    val parts = if cleaned.contains('-') then cleaned.split('-').mkString else cleaned
    if parts.length < 4 then throw new IllegalArgumentException("Input too short")

    val fromStr   = parts.take(2)
    val toStr     = parts.substring(2, 4)
    val promoChar = parts.drop(4).headOption

    val from = Pos.fromAlgebraic(fromStr).getOrElse(throw new IllegalArgumentException(s"Invalid square: $fromStr"))
    val to   = Pos.fromAlgebraic(toStr).getOrElse(throw new IllegalArgumentException(s"Invalid square: $toStr"))

    val promo = promoChar match
      case Some('q') => Some(PieceType.Queen)
      case Some('r') => Some(PieceType.Rook)
      case Some('b') => Some(PieceType.Bishop)
      case Some('n') => Some(PieceType.Knight)
      case _         => None

    Move(from, to, promo)
  }

/**
 * SAN-based move parser (e.g., "e4", "Nf3").
 */
object SanMoveParser extends MoveParser:
  import chess.model.{GameRules, MoveGenerator}
  import chess.util.Pgn
  
  def parse(input: String, state: GameState): Try[Move] = Try {
    val legal = MoveGenerator.legalMoves(state)
    val matching = legal.find { m =>
      val next = GameRules.applyMove(state, m)
      val san = Pgn.toSan(state, m, next)
      san == input || san.replace("+", "").replace("#", "") == input.replace("+", "").replace("#", "")
    }
    matching.getOrElse(throw new Exception(s"Invalid or ambiguous SAN move: $input"))
  }

/**
 * Central registry to look up available parser implementations.
 */
object ParserRegistry:
  val pgnParsers: Map[String, PgnParser] = Map(
    "regex"      -> RegexPgnParser,
    "fast"       -> FastPgnParser,
    "combinator" -> CombinatorPgnParser
  )
  
  val moveParsers: Map[String, MoveParser] = Map(
    "coordinate" -> CoordinateMoveParser,
    "san"        -> SanMoveParser
  )
