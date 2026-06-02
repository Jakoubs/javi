package chess.util.parser

import chess.model.*
import chess.util.Pgn
import scala.util.Try

/** 
 * Ein PGN-Parser, der auf Regulären Ausdrücken basiert.
 * 
 * Dieser Parser verfolgt einen "Brute-Force"-Ansatz:
 * 1. Der PGN-String wird mittels Regex von Metadaten bereinigt.
 * 2. Der verbleibende Text wird in einzelne Züge (Tokens) zerlegt.
 * 3. Für jedes Token wird simuliert, welcher legale Zug auf dem Brett dazu passt.
 */
object RegexPgnParser extends PgnParser:
  def parse(pgn: String): Try[GameState] = Try {
    // Schritt 1: Kommentare entfernen { ... }
    val noComments = pgn.replaceAll("\\{.*?\\}", "")
    
    // Schritt 2: PGN-Tags entfernen [ ... ] 
    val noTags = noComments.replaceAll("\\[.*?\\]", "")
    
    // Schritt 3: Zugnummern entfernen (z.B. "1.", "1...")
    val noNumbers = noTags.replaceAll("\\d+\\.+", "")
    
    // Schritt 4: Spielergebnisse entfernen (1-0, 0-1, 1/2-1/2, *)
    val noResults = noNumbers.replace("1-0", "").replace("0-1", "").replace("1/2-1/2", "").replace("*", "")
    
    // Die bereinigte Zeichenfolge in einzelne SAN-Tokens splitten
    val tokens = noResults.split("\\s+").filter(_.nonEmpty)
    
    var state = GameState.initial
    for token <- tokens do
      // Alle für den aktuellen Zustand legalen Züge generieren
      val legalMoves = MoveGenerator.legalMoves(state)
      
      // Wir suchen nach dem Move, dessen SAN-Darstellung zum Token passt.
      // Da Schach-Tokens manchmal '+' (Schach) oder '#' (Matt) weglassen,
      // führen wir einen toleranten Vergleich durch.
      val matchingMove = legalMoves.find { m =>
        val next = GameRules.applyMove(state, m)
        val san = Pgn.toSan(state, m, next)
        san == token || san.replace("+", "").replace("#", "") == token.replace("+", "").replace("#", "")
      }
      
      matchingMove match
        case Some(m) =>
          // Den gefundenen Zug auf das Brett anwenden
          state = GameRules.applyMove(state, m)
        case None =>
          // Falls kein Zug passt, ist das PGN entweder ungültig oder korrupt
          throw new Exception(s"Invalid or illegal PGN move: $token")
          
    state
  }
