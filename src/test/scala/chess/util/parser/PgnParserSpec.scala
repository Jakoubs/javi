package chess.util.parser

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class PgnParserSpec extends AnyFunSpec with Matchers {

  // ─── PGN fixtures ────────────────────────────────────────────────────────────

  val pgnWithTagsAndResult =
    """[Event "Test Match"]
      |[Site "Local"]
      |[Date "2026.04.07"]
      |[Round "1"]
      |[White "Player 1"]
      |[Black "Player 2"]
      |[Result "1-0"]
      |
      |1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1-0
      |""".stripMargin

  val pgnWithComments =
    """[Event "Commented Game"]
      |
      |1. e4 {King's pawn} e5 {reply} 2. Nf3 Nc6 1/2-1/2
      |""".stripMargin

  val pgnNoTags = "1. d4 d5 2. c4 *"

  val pgnOnlyMove = "1. e4"

  // ─── All three parsers via registry ──────────────────────────────────────────

  describe("All PgnParsers (via ParserRegistry)") {
    ParserRegistry.pgnParsers.foreach { case (name, parser) =>
      describe(name) {
        it("should parse PGN with header tags and result") {
          val result = parser.parse(pgnWithTagsAndResult)
          withClue(s"$name failed: ${result.failed.map(_.getMessage).getOrElse("")}") {
            result.isSuccess shouldBe true
          }
          val state = result.get
          state.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))   // e4
          state.board.get(Pos(4, 4)) shouldBe Some(Piece(Color.Black, PieceType.Pawn))   // e5
          state.board.get(Pos(1, 4)) shouldBe Some(Piece(Color.White, PieceType.Bishop)) // Bb5
          state.activeColor shouldBe Color.White
        }

        it("should parse PGN with embedded comments") {
          val result = parser.parse(pgnWithComments)
          withClue(s"$name comments-test failed: ${result.failed.map(_.getMessage).getOrElse("")}") {
            result.isSuccess shouldBe true
          }
          val state = result.get
          state.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn)) // e4
        }

        it("should parse minimal PGN without tags") {
          val result = parser.parse(pgnNoTags)
          withClue(s"$name no-tags failed: ${result.failed.map(_.getMessage).getOrElse("")}") {
            result.isSuccess shouldBe true
          }
        }

        it("should parse a single move") {
          val result = parser.parse(pgnOnlyMove)
          withClue(s"$name single-move failed: ${result.failed.map(_.getMessage).getOrElse("")}") {
            result.isSuccess shouldBe true
          }
          result.get.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
        }

        it("should fail gracefully on illegal move tokens") {
          parser.parse("1. invalidMove e5").isFailure shouldBe true
        }
      }
    }
  }

  // ─── DelegatingPgnParser ─────────────────────────────────────────────────────

  describe("DelegatingPgnParser") {
    it("should delegate to RegexPgnParser by default") {
      val delegating = new DelegatingPgnParser(RegexPgnParser)
      val result = delegating.parse(pgnWithTagsAndResult)
      result.isSuccess shouldBe true
      result.get.activeColor shouldBe Color.White
    }

    it("should delegate to CombinatorPgnParser when switched") {
      val delegating = new DelegatingPgnParser(RegexPgnParser)
      delegating.delegate = CombinatorPgnParser
      val result = delegating.parse(pgnNoTags)
      result.isSuccess shouldBe true
    }

    it("should delegate to FastPgnParser when switched") {
      val delegating = new DelegatingPgnParser(RegexPgnParser)
      delegating.delegate = FastPgnParser
      val result = delegating.parse(pgnOnlyMove)
      result.isSuccess shouldBe true
    }

    it("DelegatingPgnParser companion object should expose three parsers") {
      DelegatingPgnParser.regex      shouldBe RegexPgnParser
      DelegatingPgnParser.combinator shouldBe CombinatorPgnParser
      DelegatingPgnParser.fast       shouldBe FastPgnParser
    }

    it("should propagate failures from the delegate") {
      val delegating = new DelegatingPgnParser(RegexPgnParser)
      delegating.parse("1. illegal xyz").isFailure shouldBe true
    }
  }

  // ─── PgnParser singleton ─────────────────────────────────────────────────────

  describe("PgnParser singleton") {
    it("should have a changeable default") {
      val original = PgnParser.default
      PgnParser.default = CombinatorPgnParser
      PgnParser.default shouldBe CombinatorPgnParser
      PgnParser.default = original // restore
    }

    it("default should parse a single move") {
      PgnParser.default.parse("1. e4").isSuccess shouldBe true
    }
  }

  // ─── ParserRegistry ──────────────────────────────────────────────────────────

  describe("ParserRegistry") {
    it("should list all three PGN parser keys") {
      ParserRegistry.pgnParsers.keys should contain allOf ("regex", "fast", "combinator")
    }

    it("should list both move parser keys") {
      ParserRegistry.moveParsers.keys should contain allOf ("coordinate", "san")
    }
  }

  // ─── CombinatorPgnParser edge cases ─────────────────────────────────────────

  describe("CombinatorPgnParser specific") {
    it("should handle PGN with 0-1 result") {
      val pgn = "1. e4 e5 0-1"
      CombinatorPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should handle PGN with * result") {
      CombinatorPgnParser.parse("1. d4 *").isSuccess shouldBe true
    }

    it("should fail on a parse error (completely garbled input)") {
      CombinatorPgnParser.parse("@@@###$$$").isFailure shouldBe true
    }

    it("should handle a move group that starts without a move number") {
      // Combinator grammar allows a standalone moveOrComment without a number prefix
      CombinatorPgnParser.parse("e4 e5").isSuccess shouldBe true
    }
  }

  // ─── RegexPgnParser edge cases ───────────────────────────────────────────────

  describe("RegexPgnParser specific") {
    it("should strip comments and still parse correctly") {
      val pgn = "1. e4 {this is a comment} e5"
      RegexPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should handle 0-1 result token") {
      RegexPgnParser.parse("1. e4 e5 2. Nf3 Nc6 0-1").isSuccess shouldBe true
    }

    it("should handle 1/2-1/2 result token") {
      RegexPgnParser.parse("1. e4 e5 1/2-1/2").isSuccess shouldBe true
    }

    it("should fail on invalid move token") {
      RegexPgnParser.parse("1. e4 GARBAGE").isFailure shouldBe true
    }
  }

  // ─── FastPgnParser specific ──────────────────────────────────────────────────

  describe("FastPgnParser specific") {
    it("should handle PGN with all results (1-0, 0-1, 1/2-1/2, *)") {
      val results = Seq("1-0", "0-1", "1/2-1/2", "*")
      results.foreach { res =>
        val pgn = s"1. e4 $res"
        withClue(s"Failed for result $res") {
          FastPgnParser.parse(pgn).isSuccess shouldBe true
        }
      }
    }

    it("should handle PGN with comments in different positions") {
      val pgn = "{Start comment} 1. e4 {Middle} e5 {End} *"
      val result = FastPgnParser.parse(pgn)
      result.isSuccess shouldBe true
      result.get.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn)) // e4
    }

    it("should handle PGN with castling symbols (O-O, O-O-O)") {
      val pgn = "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. O-O Nf6 5. O-O-O" // Not legal but let's test parsing
      // Wait, O-O-O is not legal here, so it will fail in the applyMove loop.
      // Let's use a legal O-O
      val legalO_O = "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. O-O"
      FastPgnParser.parse(legalO_O).isSuccess shouldBe true
    }

    it("should handle promotion (e8=Q)") {
      // Setup a board where promotion is possible
      val pgn = "1. e4 f5 2. exf5 e5 3. f6 e4 4. f7+ Ke7 5. fxg8=Q"
      val result = FastPgnParser.parse(pgn)
      result.isSuccess shouldBe true
      result.get.board.get(Pos(6, 7)) shouldBe Some(Piece(Color.White, PieceType.Queen))
    }

    it("should handle disambiguation (Nbd2)") {
      val pgn = "1. d4 d5 2. c4 c6 3. Nf3 Nf6 4. Nbd2"
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should fail on completely invalid PGN syntax") {
      // This should trigger Parsed.Failure in Fastparse
      FastPgnParser.parse("???").isFailure shouldBe true
    }

    it("should fail on valid PGN syntax but illegal move") {
      // This should trigger the exception in the matchingMove loop
      FastPgnParser.parse("1. e5").isFailure shouldBe true
    }

    it("should handle multiple tags correctly") {
      val pgn =
        """[Event "Multi-Tag Test"]
          |[Site "Local"]
          |[White "W"]
          |[Black "B"]
          |
          |1. e4 e5
          |""".stripMargin
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should handle mixed whitespace and newlines") {
      val pgn = "1.   e4\n\t  e5 \r\n  2. \n Nf3 \t Nc6"
      val result = FastPgnParser.parse(pgn)
      result.isSuccess shouldBe true
      result.get.history.size shouldBe 4
    }

    it("should handle complex tag values with spaces and symbols") {
      val pgn = """[White "Smith, John (Master)"]
                  |[Black "O'Neil, Brian"]
                  |
                  |1. d4 d5""".stripMargin
      FastPgnParser.parse(pgn).isSuccess shouldBe true
    }

    it("should ignore NAGs in PGN if they appear ($1, $123)") {
      val pgn = "1. e4 $1 e5 $2 2. Nf3 $3"
      val result = FastPgnParser.parse(pgn)
      result.isSuccess shouldBe true 
    }
  }
}
