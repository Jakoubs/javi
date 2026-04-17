package chess.util.parser

import chess.model.GameState
import scala.util.Try

/**
 * Basis-Interface für PGN-Parser.
 * Ein PGN-Parser wandelt eine Zeichenfolge im Portable Game Notation Format
 * in einen Spielzustand (GameState) um.
 */
trait PgnParser:
  /**
   * Analysiert einen PGN-String und liefert den resultierenden GameState.
   * @param pgn Der PGN-Textinhalt.
   * @return Ein Try-Objekt, das entweder den GameState oder einen Parser-Fehler enthält.
   */
  def parse(pgn: String): Try[GameState]

object PgnParser:
  /** 
   * Der Standard-Parser, der global in der Anwendung verwendet wird.
   * Kann zur Laufzeit gegen stabilere oder schnellere Implementierungen getauscht werden.
   */
  var default: PgnParser = RegexPgnParser
