package chess.util.parser

import chess.model.*
import chess.util.Pgn
import scala.util.Try
import scala.util.parsing.combinator.RegexParsers

/**
 * Ein PGN-Parser, der auf den klassischen Scala Parser Combinators basiert.
 * 
 * Dieser Parser nutzt eine funktionale DSL, um die Grammatik von PGN-Dateien
 * abzubilden. Er bietet eine gute Lesbarkeit und eine klare Struktur.
 */
object CombinatorPgnParser extends PgnParser with RegexParsers:

  override def skipWhitespace = true
  
  // -- Grammatik Definitionen --
  
  /** 
   * Die Wurzel-Regel für ein PGN-Dokument.
   * Liest erst alle Tags, dann die Zug-Gruppen und wendet sie sequenziell auf den Initialzustand an.
   */
  def pgn: Parser[GameState] = 
    rep(tag) ~> rep(moveGroup) <~ opt(result) ^^ { groups =>
      var state = GameState.initial
      for token <- groups.flatten do
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

  /** Erkennt PGN-Tags wie [White "Deep Blue"] */
  def tag: Parser[String] = "[" ~> """[^\]]*""".r <~ "]"
  
  /** 
   * Gruppiert Züge, die einer Zugnummer folgen. 
   * Beispiel: "1. e4 e5" liefert List("e4", "e5")
   */
  def moveGroup: Parser[List[String]] = 
    """\d+\.+""".r ~> rep(moveOrComment) ^^ { list => 
      list.collect { case Right(m) => m } 
    } | moveOrComment ^^ { 
      case Right(m) => List(m)
      case Left(_)  => Nil 
    }

  /** Unterscheidet zwischen einem Zug (SAN) und einem Kommentar. */
  def moveOrComment: Parser[Either[String, String]] = 
    comment ^^ (c => Left(c)) | san ^^ (s => Right(s))

  /** Erkennt Kommentare in geschweiften Klammern { ... } */
  def comment: Parser[String] = "{" ~> """[^}]*""".r <~ "}"
  
  /** Regex-basierter Parser für einen SAN-Zug (z.B. Nf3, O-O, e4). */
  def san: Parser[String] = """(O-O-O|O-O|([NBRQK]?[a-h]?[1-8]?x?[a-h][1-8](=[NBRQ])?[+#]?))""".r

  /** Erkennt das Ergebnis der Partie. */
  def result: Parser[String] = "1-0" | "0-1" | "1/2-1/2" | "*"

  def parse(pgnString: String): Try[GameState] = 
    parseAll(pgn, pgnString) match
      case Success(result, _) => Try(result)
      case failure: NoSuccess => scala.util.Failure(new Exception(s"Parse error: ${failure.msg}"))
