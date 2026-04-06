package chess.util.parser

import chess.model.*
import chess.util.Pgn
import scala.util.Try
import scala.util.parsing.combinator.RegexParsers

/**
 * A structured PGN parser using Scala Parser Combinators.
 */
object CombinatorPgnParser extends PgnParser with RegexParsers:

  override def skipWhitespace = true
  
  // -- Grammar --
  
  def pgn: Parser[GameState] = 
    rep(tag) ~> rep(moveGroup) <~ opt(result) ^^ { groups =>
      var state = GameState.initial
      for token <- groups.flatten do
        val legalMoves = MoveGenerator.legalMoves(state)
        val matchingMove = legalMoves.find { m =>
          val next = GameRules.applyMove(state, m)
          val san = Pgn.toSan(state, m, next)
          // Relaxed matching for check/mate symbols
          san == token || san.replace("+", "").replace("#", "") == token.replace("+", "").replace("#", "")
        }
        
        matchingMove match
          case Some(m) => state = GameRules.applyMove(state, m)
          case None    => throw new Exception(s"Illegal move in PGN: $token")
      state
    }

  def tag: Parser[String] = "[" ~> """[^\]]*""".r <~ "]"
  
  def moveGroup: Parser[List[String]] = 
    """\d+\.+""".r ~> rep(moveOrComment) ^^ { list => 
      list.collect { case Right(m) => m } 
    } | moveOrComment ^^ { 
      case Right(m) => List(m)
      case Left(_)  => Nil 
    }

  def moveOrComment: Parser[Either[String, String]] = 
    comment ^^ (c => Left(c)) | san ^^ (s => Right(s))

  def comment: Parser[String] = "{" ~> """[^}]*""".r <~ "}"
  
  def san: Parser[String] = """(O-O-O|O-O|([NBRQK]?[a-h]?[1-8]?x?[a-h][1-8](=[NBRQ])?[+#]?))""".r

  def result: Parser[String] = "1-0" | "0-1" | "1/2-1/2" | "*"

  def parse(pgnString: String): Try[GameState] = 
    parseAll(pgn, pgnString) match
      case Success(result, _) => Try(result)
      case Failure(msg, _)    => scala.util.Failure(new Exception(s"Parse failure: $msg"))
      case Error(msg, _)      => scala.util.Failure(new Exception(s"Parse error: $msg"))
