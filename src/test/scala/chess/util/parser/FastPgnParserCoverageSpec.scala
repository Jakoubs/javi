package chess.util.parser

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/**
 * Zusätzliche Tests für FastPgnParser – zielt auf die bisher nicht abgedeckten
 * internen Grammatikpfade des Parsers ab (58.5% Statement-Coverage).
 *
 * Nicht abgedeckte Bereiche laut Report:
 *  - Zeile 50: collect-Branch 1 (filter .-enthaltende Strings)
 *  - Zeile 51: collect-Branch 2 (O-O / O-O-O durchlassen)
 *  - Verschiedene Kombinationen von tags, ws, comment innerhalb pgn()
 */
class FastPgnParserCoverageSpec extends AnyFunSpec with Matchers {

  // ── Vollständige Spiele mit vielen Grammatikpfaden ───────────────────────────

  describe("FastPgnParser full-game coverage paths") {

    it("should parse a multi-tag PGN with ws between tags and after") {
      val pgn =
        """[Event "Test"]
          |[White "Alice"]
          |[Black "Bob"]
          |[Result "1-0"]
          |
          |1. e4 e5 2. Nf3 Nc6 1-0""".stripMargin
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should parse a game with multiple inline comments") {
      val pgn = "1. e4 {best by test} e5 {symmetric} 2. Nf3 {development} Nc6 *"
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should parse a game with a comment at the very start") {
      val pgn = "{Opening remark} 1. e4 e5 *"
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should parse a game ending in 0-1 (exercises '-' filter keeping result out)") {
      val pgn = "1. e4 e5 2. Nf3 Nf6 0-1"
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should parse a game ending in 1/2-1/2") {
      val pgn = "1. e4 e5 1/2-1/2"
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should parse a game with leading and trailing whitespace") {
      val pgn = "  \n  1. e4 e5  \n  "
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should parse a PGN with tags only (no moves, just a result)") {
      val pgn = "[Event \"Empty\"] *"
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should parse capture move (exd5) exercising sanChar 'x'") {
      val pgn = "1. e4 d5 2. exd5 Qxd5 3. Nc3"
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should parse pawn promotion (e8=Q) exercising sanChar '='") {
      // Setup a position near promotion via FEN then parse a continuation
      // Actually use FastPgnParser.parse with a short game that reaches promotion
      // Shortest promotion: use a pre-set board via FEN approach isn't available here.
      // Instead test the grammar primitive: san should match "e8=Q"
      import fastparse.*
      val r = fastparse.parse("e8=Q", FastPgnParser.san(using _))
      r.isSuccess shouldBe true
      r.get.value shouldBe "e8=Q"
    }

    it("should parse check annotation (+) via sanChar") {
      import fastparse.*
      val r = fastparse.parse("Nf3+", FastPgnParser.san(using _))
      r.isSuccess shouldBe true
      r.get.value shouldBe "Nf3+"
    }

    it("should parse checkmate annotation (#) via sanChar") {
      import fastparse.*
      val r = fastparse.parse("Qh4#", FastPgnParser.san(using _))
      r.isSuccess shouldBe true
      r.get.value shouldBe "Qh4#"
    }

    it("should parse disambiguation move (Nbd2) via sanChar") {
      import fastparse.*
      val r = fastparse.parse("Nbd2", FastPgnParser.san(using _))
      r.isSuccess shouldBe true
      r.get.value shouldBe "Nbd2"
    }

    it("collect: result token '1-0' is filtered (contains '-')") {
      // After parsing "1. e4 1-0", the collect keeps only 'e4'
      val result = FastPgnParser.parse("1. e4 1-0")
      result.isSuccess shouldBe true
      result.get.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    }

    it("collect: '*' token is filtered (equals '*')") {
      val result = FastPgnParser.parse("1. e4 e5 *")
      result.isSuccess shouldBe true
    }

    it("collect: move-number tokens are filtered (contain '.')") {
      // "1.", "2." etc. are parsed as moveNum and should be excluded from move list
      // Legal sequence: 1. d4 d5 2. Nf3 Nf6
      val result = FastPgnParser.parse("1. d4 d5 2. Nf3 Nf6")
      result.isSuccess shouldBe true
      result.get.board.get(Pos(3, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
      result.get.board.get(Pos(3, 4)) shouldBe Some(Piece(Color.Black, PieceType.Pawn))
    }

    it("should handle ws1 between multiple header tags") {
      import fastparse.*
      val input = "[A \"1\"]\t[B \"2\"]\n[C \"3\"]"
      val r = fastparse.parse(input, FastPgnParser.tags(using _))
      r.isSuccess shouldBe true
    }
  }

  // ── moveNum grammar variants ──────────────────────────────────────────────────

  describe("FastPgnParser.moveNum grammar") {
    it("should parse '1. ' with trailing space") {
      import fastparse.*
      val r = fastparse.parse("1. ", FastPgnParser.moveNum(using _))
      r.isSuccess shouldBe true
    }

    it("should parse '10.' (two digit move number)") {
      import fastparse.*
      val r = fastparse.parse("10.", FastPgnParser.moveNum(using _))
      r.isSuccess shouldBe true
    }

    it("should parse '1...' (Black move indicator)") {
      import fastparse.*
      val r = fastparse.parse("1...", FastPgnParser.moveNum(using _))
      r.isSuccess shouldBe true
    }
  }

  // ── pgn grammar with ws-only input ───────────────────────────────────────────

  describe("FastPgnParser.pgn edge cases") {
    it("should succeed with completely empty input") {
      val result = FastPgnParser.parse("")
      result.isSuccess shouldBe true
      result.get.board shouldBe GameState.initial.board
    }

    it("should succeed with only whitespace") {
      val result = FastPgnParser.parse("   \n\t  ")
      result.isSuccess shouldBe true
    }
  }
}
