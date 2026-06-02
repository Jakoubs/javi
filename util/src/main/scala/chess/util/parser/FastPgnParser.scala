package chess.util.parser

import chess.model.*
import chess.util.Pgn
import scala.util.Try
import fastparse.*, NoWhitespace.*

/**
 * Ein Hochleistungs-PGN-Parser, der auf der Fastparse-Bibliothek basiert.
 * 
 * Dieser Parser definiert eine formale Grammatik für das PGN-Format, was ihn
 * schneller und präziser als den Regex-basierten Parser macht.
 */
object FastPgnParser extends PgnParser:

  // -- Whitespace Handling --
  /** Erkennt Leerzeichen, Tabs und Zeilenumbrüche. */
  def ws(using ctx: P[?]) = P(CharsWhileIn(" \t\r\n", 0))
  def ws1(using ctx: P[?]) = P(CharsWhileIn(" \t\r\n", 1))

  // -- Grammatik-Komponenten --
  
  /** Erkennt einen PGN-Tag like [Event "F/S Return Match"] */
  def tag(using ctx: P[?]) = P("[" ~ CharsWhile(c => c != ']') ~ "]").!
  def tags(using ctx: P[?]) = P(tag.rep(sep = ws))
  
  /** Erkennt Zugnummern wie "1.", "1..." oder "20." */
  def moveNum(using ctx: P[?]) = P(CharsWhileIn("0-9").rep(1) ~ "." ~ " ".? ~ ".".rep(0)).!
  
  /** Erkennt Kommentare in geschweiften Klammern { ... } */
  def comment(using ctx: P[?]) = P("{" ~ CharsWhile(c => c != '}') ~ "}").!
  
  /** Erkennt Zeichen, die in einer Standard-Algebraischen-Notation (SAN) vorkommen. */
  def sanChar(using ctx: P[?]) = P(CharIn("a-h1-8NBRQKx=+#\\-"))
  
  /** Erkennt einen kompletten SAN-Zug oder eine Rochade. */
  def san(using ctx: P[?]) = P(("O-O-O" | "O-O" | sanChar.rep(1)).!)
  
  /** Erkennt das Ergebnis einer Partie. */
  def result(using ctx: P[?]) = P(("1-0" | "0-1" | "1/2-1/2" | "*").!)
  
  /** 
   * Die Haupt-Grammatikregel für ein komplettes PGN-Dokument.
   * Filtert Metadaten und Kommentare heraus und liefert nur die Liste der Züge.
   */
  def pgn(using ctx: P[?]): P[List[String]] = P(
    ws ~ tags.? ~ (ws ~ (moveNum | comment | result | san)).rep ~ ws ~ End
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
              // Toleranter Vergleich (ignoriert Schach- und Matt-Symbole falls nötig)
              san == token || san.replace("+", "").replace("#", "") == token.replace("+", "").replace("#", "")
            }
            
            matchingMove match
              case Some(m) => state = GameRules.applyMove(state, m)
              case None    => throw new Exception(s"Illegal move in PGN: $token")
          state
        }
      case f: Parsed.Failure => 
        scala.util.Failure(new Exception(s"Fastparse failure: ${f.msg}"))
