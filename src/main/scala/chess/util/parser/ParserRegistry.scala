package chess.util.parser

import chess.model.{GameState, Move}
import scala.util.Try

/**
 * Gemeinsames Interface für verschiedene Zug-Parsing-Strategien.
 * Dies wird hauptsächlich für die Direkteingabe von Zügen (TUI/GUI) verwendet.
 */
trait MoveParser:
  /**
   * Analysiert einen Eingabestring im Kontext eines aktuellen Spielfelds.
   * @param input Der Zug-String (z.B. "e2e4" oder "Nf3").
   * @param state Der aktuelle Zustand des Spiels.
   * @return Ein Try-Objekt mit dem Move oder einem Fehler.
   */
  def parse(input: String, state: GameState): Try[Move]

/**
 * Ein Parser für Koordinaten-basierte Züge (UCI-Format).
 * Beispiel: "e2e4", "e7e8q" (Umwandlung in Dame).
 */
object CoordinateMoveParser extends MoveParser:
  import chess.model.{Pos, PieceType}
  
  def parse(input: String, state: GameState): Try[Move] = Try {
    val cleaned = input.trim.toLowerCase
    // Bindestriche entfernen, falls vorhanden (z.B. e2-e4 -> e2e4)
    val parts = if cleaned.contains('-') then cleaned.split('-').mkString else cleaned
    
    if parts.length < 4 then throw new IllegalArgumentException("Eingabe zu kurz für Koordinaten-Format")

    val fromStr   = parts.take(2)
    val toStr     = parts.substring(2, 4)
    val promoChar = parts.drop(4).headOption

    val from = Pos.fromAlgebraic(fromStr).getOrElse(throw new IllegalArgumentException(s"Ungültiges Startfeld: $fromStr"))
    val to   = Pos.fromAlgebraic(toStr).getOrElse(throw new IllegalArgumentException(s"Ungültiges Zielfeld: $toStr"))

    // Bestimmung der Umwandlungsfigur (Promotion)
    val promo = promoChar match
      case Some('q') => Some(PieceType.Queen)
      case Some('r') => Some(PieceType.Rook)
      case Some('b') => Some(PieceType.Bishop)
      case Some('n') => Some(PieceType.Knight)
      case _         => None

    Move(from, to, promo)
  }

/**
 * Ein Parser für Züge in der Standard-Algebraischen-Notation (SAN).
 * Beispiel: "e4", "Nf3", "O-O", "Bxe5+".
 */
object SanMoveParser extends MoveParser:
  import chess.model.{GameRules, MoveGenerator}
  import chess.util.Pgn
  
  def parse(input: String, state: GameState): Try[Move] = Try {
    // Alle legalen Züge für die aktuelle Position generieren
    val legal = MoveGenerator.legalMoves(state)
    
    // Den legalen Zug suchen, dessen SAN-String mit der Eingabe übereinstimmt.
    val matching = legal.find { m =>
      val next = GameRules.applyMove(state, m)
      val san = Pgn.toSan(state, m, next)
      // Vergleich inklusive Toleranz für Schach- (+) und Matt-Symbole (#)
      san == input || san.replace("+", "").replace("#", "") == input.replace("+", "").replace("#", "")
    }
    matching.getOrElse(throw new Exception(s"Ungültiger oder mehrdeutiger SAN-Zug: $input"))
  }

/**
 * Zentrales Register zur Verwaltung aller verfügbaren Parser-Implementierungen.
 */
object ParserRegistry:
  /** Mapping von Namen auf PGN-Parser Strategien. */
  val pgnParsers: Map[String, PgnParser] = Map(
    "regex"      -> RegexPgnParser,
    "fast"       -> FastPgnParser,
    "combinator" -> CombinatorPgnParser
  )
  
  /** Mapping von Namen auf Einzelzug-Parser Strategien. */
  val moveParsers: Map[String, MoveParser] = Map(
    "coordinate" -> CoordinateMoveParser,
    "san"        -> SanMoveParser
  )
