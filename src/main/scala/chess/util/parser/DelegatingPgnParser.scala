package chess.util.parser

import chess.model.GameState
import scala.util.Try

/**
 * A parser that delegates to one of the specific implementations.
 * Useful for switching the strategy at runtime.
 */
class DelegatingPgnParser(var delegate: PgnParser) extends PgnParser:
  def parse(pgn: String): Try[GameState] = delegate.parse(pgn)

object DelegatingPgnParser:
  val regex = RegexPgnParser
  val combinator = CombinatorPgnParser
  val fast = FastPgnParser
