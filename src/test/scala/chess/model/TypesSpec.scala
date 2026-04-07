package chess.model

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class TypesSpec extends AnyFunSpec with Matchers {
  describe("Color") {
    it("should toggle opposite color") {
      Color.White.opposite shouldBe Color.Black
      Color.Black.opposite shouldBe Color.White
    }
  }

  describe("Piece") {
    it("should parse all piece symbols correctly") {
      Piece.fromFenChar('K') shouldBe Some(Piece(Color.White, PieceType.King))
      Piece.fromFenChar('q') shouldBe Some(Piece(Color.Black, PieceType.Queen))
      Piece.fromFenChar('n') shouldBe Some(Piece(Color.Black, PieceType.Knight))
      Piece.fromFenChar('P') shouldBe Some(Piece(Color.White, PieceType.Pawn))
    }
    it("should return None for invalid characters") {
      Piece.fromFenChar('x') shouldBe None
    }
    it("should provide correct symbols and letters") {
      Piece(Color.White, PieceType.King).symbol shouldBe "♚"
      Piece(Color.White, PieceType.Queen).symbol shouldBe "♛"
      Piece(Color.White, PieceType.Rook).symbol shouldBe "♜"
      Piece(Color.White, PieceType.Bishop).symbol shouldBe "♝"
      Piece(Color.White, PieceType.Knight).symbol shouldBe "♞"
      Piece(Color.White, PieceType.Pawn).symbol shouldBe "♟"
      Piece(Color.Black, PieceType.Pawn).letter shouldBe "p"
    }

    it("should return None for non-alphabetic characters in fromFenChar") {
      // Triggering the 'return None' branch for non-alphabetic characters
      Piece.fromFenChar('1') shouldBe None
      Piece.fromFenChar('!') shouldBe None
    }
  }

  describe("Pos") {
    it("should convert to and from algebraic notation") {
      val pos = Pos(0, 0) // a1
      pos.toAlgebraic shouldBe "a1"
      Pos.fromAlgebraic("h8") shouldBe Some(Pos(7, 7))
    }
    it("should handle invalid coordinates and lengths") {
      Pos(-1, 0).isValid shouldBe false
      Pos(8, 0).isValid shouldBe false
      Pos.fromAlgebraic("z9") shouldBe None
      Pos.fromAlgebraic("a11") shouldBe None // Length != 2
      Pos.fromAlgebraic("a") shouldBe None   // Length != 2
    }
    it("should support position addition") {
      Pos(1, 1) + (1, 2) shouldBe Pos(2, 3)
    }
  }

  describe("Move") {
    it("should convert to accurate input string") {
      Move(Pos(0, 1), Pos(0, 2)).toInputString shouldBe "a2a3"
      Move(Pos(0, 6), Pos(0, 7), Some(PieceType.Queen)).toInputString shouldBe "a7a8q"
      Move(Pos(0, 6), Pos(0, 7), Some(PieceType.Rook)).toInputString shouldBe "a7a8r"
      Move(Pos(0, 6), Pos(0, 7), Some(PieceType.Bishop)).toInputString shouldBe "a7a8b"
      Move(Pos(0, 6), Pos(0, 7), Some(PieceType.Knight)).toInputString shouldBe "a7a8n"
    }
  }

  describe("ClockState") {
    it("should return the correct active time") {
      val clock = ClockState(1000, 2000, 500)
      clock.activeMillis(Color.White) shouldBe 1000
      clock.activeMillis(Color.Black) shouldBe 2000
    }
  }

  describe("CastlingRights") {
    it("should generate correct FEN strings") {
      CastlingRights().toFen shouldBe "KQkq"
      CastlingRights(false, false, false, false).toFen shouldBe "-"
    }
    it("should parse FEN strings correctly") {
      CastlingRights.fromFen("KQkq") shouldBe Some(CastlingRights())
      CastlingRights.fromFen("-") shouldBe Some(CastlingRights(false, false, false, false))
      CastlingRights.fromFen("invalid") shouldBe None
    }
    it("should correctly disable rights") {
      CastlingRights().disableWhite.whiteKingSide shouldBe false
      CastlingRights().disableBlack.blackQueenSide shouldBe false
      CastlingRights().disableWhiteKingSide.whiteKingSide shouldBe false
    }
  }
}
