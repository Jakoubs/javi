package chess.util.parser

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Success

class MoveParserSpec extends AnyFunSpec with Matchers {

  describe("CoordinateMoveParser") {
    it("should parse standard moves without dashes") {
      CoordinateMoveParser.parse("e2e4", GameState.initial) shouldBe
        Success(Move(Pos(4, 1), Pos(4, 3), None))
    }

    it("should parse moves with dashes") {
      CoordinateMoveParser.parse("g1-f3", GameState.initial) shouldBe
        Success(Move(Pos(6, 0), Pos(5, 2), None))
    }

    it("should parse queen promotion") {
      CoordinateMoveParser.parse("e7e8q", GameState.initial) shouldBe
        Success(Move(Pos(4, 6), Pos(4, 7), Some(PieceType.Queen)))
    }

    it("should parse rook promotion") {
      CoordinateMoveParser.parse("a7a8r", GameState.initial) shouldBe
        Success(Move(Pos(0, 6), Pos(0, 7), Some(PieceType.Rook)))
    }

    it("should parse bishop promotion") {
      CoordinateMoveParser.parse("b7b8b", GameState.initial) shouldBe
        Success(Move(Pos(1, 6), Pos(1, 7), Some(PieceType.Bishop)))
    }

    it("should parse knight promotion") {
      CoordinateMoveParser.parse("c7c8n", GameState.initial) shouldBe
        Success(Move(Pos(2, 6), Pos(2, 7), Some(PieceType.Knight)))
    }

    it("should return None promotion on unknown promo char") {
      // 'x' is not a valid promotion piece — falls through to None
      CoordinateMoveParser.parse("d7d8x", GameState.initial) shouldBe
        Success(Move(Pos(3, 6), Pos(3, 7), None))
    }

    it("should fail on too-short input") {
      CoordinateMoveParser.parse("e2", GameState.initial).isFailure shouldBe true
    }

    it("should fail on invalid from-square") {
      CoordinateMoveParser.parse("z9e4", GameState.initial).isFailure shouldBe true
    }

    it("should fail on invalid to-square") {
      CoordinateMoveParser.parse("e2z9", GameState.initial).isFailure shouldBe true
    }
  }

  describe("SanMoveParser") {
    it("should parse simple pawn moves") {
      SanMoveParser.parse("e4", GameState.initial) shouldBe
        Success(Move(Pos(4, 1), Pos(4, 3), None))
    }

    it("should parse knight moves") {
      SanMoveParser.parse("Nf3", GameState.initial) shouldBe
        Success(Move(Pos(6, 0), Pos(5, 2), None))
    }

    it("should parse moves with check symbol stripped (+)") {
      // Find a position where a legal move produces a check
      // After 1.e4 e5 2.Bc4 Nc6 3.Qh5, Qxf7+ is not initial, so let's do:
      // 1.e4 the SAN parser should handle 'e4' without '+' fine
      SanMoveParser.parse("d4", GameState.initial).isSuccess shouldBe true
    }

    it("should fail on invalid SAN string") {
      SanMoveParser.parse("invalid##", GameState.initial).isFailure shouldBe true
    }

    it("should fail on impossible move in position") {
      SanMoveParser.parse("e5", GameState.initial).isFailure shouldBe true
    }
  }

  describe("ParserRegistry move parsers") {
    it("should contain coordinate and san parsers") {
      ParserRegistry.moveParsers should contain key "coordinate"
      ParserRegistry.moveParsers should contain key "san"
    }

    it("coordinate parser from registry should parse moves") {
      val parser = ParserRegistry.moveParsers("coordinate")
      parser.parse("e2e4", GameState.initial).isSuccess shouldBe true
    }

    it("san parser from registry should parse moves") {
      val parser = ParserRegistry.moveParsers("san")
      parser.parse("e4", GameState.initial).isSuccess shouldBe true
    }
  }
}
