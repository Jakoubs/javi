package chess.util.parser

import chess.model.GameState
import scala.util.Try

/**
 * Ein Proxy-Parser, der die eigentliche Parsing-Arbeit an eine konkrete
 * Implementierung (Delegate) delegiert. 
 * Dies ermöglicht es, die Parsing-Strategie zur Laufzeit dynamisch zu ändern,
 * ohne Abhängigkeiten im restlichen Code anpassen zu müssen.
 */
class DelegatingPgnParser(var delegate: PgnParser) extends PgnParser:
  /**
   * Leitet den Parsing-Aufruf an den aktuell gesetzten Delegate weiter.
   */
  def parse(pgn: String): Try[GameState] = delegate.parse(pgn)

object DelegatingPgnParser:
  /** Zugriff auf die Regex-basierte Strategie. */
  val regex = RegexPgnParser
  
  /** Zugriff auf die Parser-Combinator Strategie. */
  val combinator = CombinatorPgnParser
  
  /** Zugriff auf die Fastparse-basierte Strategie. */
  val fast = FastPgnParser
