package chess.util.parser

import chess.model.*
import chess.util.Pgn
import scala.util.Try
import fastparse.*, NoWhitespace.*

/**
 * A high-performance PGN parser using Fastparse.
 * Updated for Scala 3 / Fastparse 3 syntax.
 */
object FastPgnParser extends PgnParser:

  // -- Whitespace Handling --
  def ws(using ctx: P[?]) = P(CharsWhileIn(" \t\r\n", 0))
  def ws1(using ctx: P[?]) = P(CharsWhileIn(" \t\r\n", 1))

  // -- Grammar Components --
  def tag(using ctx: P[?]) = P("[" ~ CharsWhile(c => c != ']') ~ "]").!
  def tags(using ctx: P[?]) = P(tag.rep(sep = ws))
  
  def moveNum(using ctx: P[?]) = P(CharsWhileIn("0-9").rep(1) ~ "." ~ " ".? ~ ".".rep(0)).!
  def comment(using ctx: P[?]) = P("{" ~ CharsWhile(c => c != '}') ~ "}").!
  
  def sanChar(using ctx: P[?]) = P(CharIn("a-h1-8NBRQKx=+#\\-"))
  def san(using ctx: P[?]) = P(("O-O-O" | "O-O" | sanChar.rep(1)).!)
  
  def result(using ctx: P[?]) = P(("1-0" | "0-1" | "1/2-1/2" | "*").!)
  
  def pgn(using ctx: P[?]): P[List[String]] = P(
    ws ~ tags.? ~ (ws ~ (moveNum | comment | san | result)).rep ~ ws ~ End
  ).map { case (_, moves) =>
    moves.collect {
      case s: String if !s.contains(".") && !s.startsWith("{") && !s.contains("-") && s != "*" => s
      case s: String if s == "O-O" || s == "O-O-O" => s
    }.toList
  }

  def parse(pgnString: String): Try[GameState] = 
    fastparse.parse(pgnString, pgn(using _)) match
      case Parsed.Success(tokens, _) => 
        Try {
          var state = GameState.initial
          for token <- tokens do
            val legalMoves = MoveGenerator.legalMoves(state)
            val matchingMove = legalMoves.find { m =>
              val next = GameRules.applyMove(state, m)
              val san = Pgn.toSan(state, m, next)
              san == token || san.replace("+", "").replace("#", "") == token.replace("+", "").replace("#", "")
            }
            
            matchingMove match
              case Some(m) => state = GameRules.applyMove(state, m)
              case None    => throw new Exception(s"Illegal move in PGN: $token")
          state
        }
      case f: Parsed.Failure => 
        scala.util.Failure(new Exception(s"Fastparse failure: ${f.msg}"))
