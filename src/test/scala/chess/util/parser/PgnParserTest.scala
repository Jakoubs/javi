package chess.util.parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import chess.model.Color

class PgnParserTest extends AnyFunSuite with Matchers:

  val simplePgn = "[Event \"Test\"]\n1. e4 e5 2. Nf3 Nc6 *"
  val pgnWithComments = "[Event \"Test\"]\n1. e4 {Best move} e5 2. Nf3 Nc6 1-0"
  
  def testParser(name: String, parser: PgnParser): Unit =
    test(s"$name should parse a simple PGN") {
      val state = parser.parse(simplePgn).get
      state.fullMoveNumber shouldBe 3 // After Nf3 Nc6, it's White's turn for move 3
      state.activeColor shouldBe Color.White
    }

    test(s"$name should handle comments and results") {
      val state = parser.parse(pgnWithComments).get
      state.activeColor shouldBe Color.White
      state.fullMoveNumber shouldBe 3
    }

  testParser("RegexPgnParser", RegexPgnParser)
  testParser("CombinatorPgnParser", CombinatorPgnParser)
  testParser("FastPgnParser", FastPgnParser)
