package chess.util.parser

import chess.model.*
import chess.util.Pgn
import scala.util.Try

/** 
 * A legacy-inspired PGN parser using regular expressions for cleaning 
 * and brute-force legal move matching.
 */
object RegexPgnParser extends PgnParser:
  def parse(pgn: String): Try[GameState] = Try {
    // 1. Remove comments { ... }
    val noComments = pgn.replaceAll("\\{.*?\\}", "")
    // 2. Remove tags [ ... ] 
    val noTags = noComments.replaceAll("\\[.*?\\]", "")
    // 3. Remove move numbers like 1. or 1...
    val noNumbers = noTags.replaceAll("\\d+\\.+", "")
    // 4. Remove results
    val noResults = noNumbers.replace("1-0", "").replace("0-1", "").replace("1/2-1/2", "").replace("*", "")
    
    // Split into SAN tokens
    val tokens = noResults.split("\\s+").filter(_.nonEmpty)
    
    var state = GameState.initial
    for token <- tokens do
      val legalMoves = MoveGenerator.legalMoves(state)
      // We search for the move whose SAN-string matches the token.
      // If + or # are missing in the token, we tolerate it with pure comparison.
      val matchingMove = legalMoves.find { m =>
        val next = GameRules.applyMove(state, m)
        val san = Pgn.toSan(state, m, next)
        san == token || san.replace("+", "").replace("#", "") == token.replace("+", "").replace("#", "")
      }
      
      matchingMove match
        case Some(m) =>
          state = GameRules.applyMove(state, m)
        case None =>
          throw new Exception(s"Invalid or illegal PGN move: $token")
          
    state
  }
