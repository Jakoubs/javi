package chess.util.parser

import chess.model.GameState
import scala.util.Try

trait PgnParser:
  def parse(pgn: String): Try[GameState]

object PgnParser:
  /** The default parser to use. Can be changed globally. */
  var default: PgnParser = RegexPgnParser
