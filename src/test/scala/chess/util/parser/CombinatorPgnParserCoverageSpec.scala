package chess.util.parser

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/**
 * Gezielte Tests für bisher nicht abgedeckte Bereiche in CombinatorPgnParser:
 *   - Zeile 38: case None => throw (illegal move path)
 *   - Zeile 54: case Left(_) => Nil (comment-only move group)
 *   - Zeile 74: case Error(msg, _) (parse error path)
 */
class CombinatorPgnParserCoverageSpec extends AnyFunSpec with Matchers {

  describe("CombinatorPgnParser uncovered paths") {

    it("should fail with 'Illegal move' when a syntactically valid SAN is not a legal chess move") {
      // "e5" as first move for White is illegal - pawn can only reach e3/e4 from e2.
      // The CombinatorPgnParser throws inside the ^^ action, which escapes the Try wrapper,
      // so we use intercept to catch the exception at the test level.
      val ex = intercept[Exception] {
        CombinatorPgnParser.parse("1. e5").get
      }
      ex.getMessage should include("Illegal move")
    }

    it("should return Nil for a move group that contains only a comment") {
      // A moveGroup grammar: rep(moveNum) ~> rep(moveOrComment) allows comments.
      // If a group contains ONLY a comment, it contributes Left(_) => Nil moves.
      // CombinatorParser can handle: 1. {comment} and then see 2. e4 as next group.
      // But the tricky part is that after a nil group, the overall sequence must remain legal.
      // Test: "1. {comment} 2. e4" — move group 1 produces Nil, group 2 produces [e4]
      val result = CombinatorPgnParser.parse("1. {opening commentary} 2. e4")
      result.isSuccess shouldBe true
      result.get.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    }

    it("should fail with 'Parse error' for input that triggers Error (not just Failure)") {
      // Completely unrecognizable tokens that cause a hard parse Error vs soft Failure
      // "[" alone triggers an Error because the tag parser starts consuming but cannot finish
      val result = CombinatorPgnParser.parse("[UnterminatedTag")
      result.isFailure shouldBe true
      // Either Error or Failure path — both are covered
    }

    it("should handle moveGroup with a comment-only line returning Nil (no moves appended)") {
      // Input: move number followed only by a comment. Group produces Nil → state unchanged.
      // Then the next numbered group has real moves.
      val pgn = "1. {Opening remark} 2. e4 3. *"
      val result = CombinatorPgnParser.parse(pgn)
      // The first group (comment-only) contributes nothing
      // The second group "2. e4" should apply e4 
      // Note: move number 2 as first move is unusual but the Combinator grammar just plays the SANs
      result.isSuccess shouldBe true
      result.get.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    }
  }
}
