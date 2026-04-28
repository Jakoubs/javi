package chess.util.parser

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class CombinatorPgnParserCoverageSpec extends AnyFunSpec with Matchers {

  describe("CombinatorPgnParser uncovered paths") {

    it("should fail with 'Illegal move' when a syntactically valid SAN is not a legal chess move") {
      val ex = intercept[Exception] {
        CombinatorPgnParser.parse("1. e5").get
      }
      ex.getMessage should include("Illegal move")
    }

    it("should return Nil for a move group that contains only a comment") {
      val result = CombinatorPgnParser.parse("1. {opening commentary} 2. e4")
      result.isSuccess shouldBe true
      result.get.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    }

    it("should fail with 'Parse error' for input that triggers Error (not just Failure)") {
      val result = CombinatorPgnParser.parse("[Event \"Broken\"")
      result.isFailure shouldBe true
      result.failed.get.getMessage should include("Parse error")
    }

    it("should handle moveGroup with a comment-only line returning Nil (no moves appended)") {
      val pgn = "1. {Opening remark} 2. e4 3. *"
      val result = CombinatorPgnParser.parse(pgn)
      result.isSuccess shouldBe true
      result.get.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    }
  }
}
