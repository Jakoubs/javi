package chess.util.parser

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/**
 * Gezielte Tests für FastPgnParser – fokussiert auf alle Grammatik-Methoden
 * und Randfälle, die im Coverage-Report als nicht abgedeckt markiert sind:
 *   ws, ws1, tag, tags, moveNum, comment, sanChar, san, result, pgn (Zeilen 18–53)
 */
class FastPgnParserSpec extends AnyFunSpec with Matchers {

  // ─── Grammatik-Methoden direkt via fastparse testen ─────────────────────────

  describe("FastPgnParser grammar primitives") {

    it("ws should accept zero whitespace") {
      import fastparse.*
      val r = fastparse.parse("abc", FastPgnParser.ws(using _))
      r.isSuccess shouldBe true
    }

    it("ws should accept spaces, tabs, newlines") {
      import fastparse.*
      val r = fastparse.parse("   \t\n", FastPgnParser.ws(using _))
      r.isSuccess shouldBe true
    }

    it("ws1 should require at least one whitespace") {
      import fastparse.*
      val r1 = fastparse.parse(" \t", FastPgnParser.ws1(using _))
      r1.isSuccess shouldBe true
      val r2 = fastparse.parse("abc", FastPgnParser.ws1(using _))
      r2.isSuccess shouldBe false
    }

    it("tag should parse a PGN tag like [Event \"Match\"]") {
      import fastparse.*
      val r = fastparse.parse("[Event \"Match\"]", FastPgnParser.tag(using _))
      r.isSuccess shouldBe true
      r.get.value should include("Event")
    }

    it("tags should parse multiple tags separated by whitespace") {
      import fastparse.*
      val input = "[White \"Alice\"] [Black \"Bob\"]"
      val r = fastparse.parse(input, FastPgnParser.tags(using _))
      r.isSuccess shouldBe true
    }

    it("moveNum should parse '1.'") {
      import fastparse.*
      val r = fastparse.parse("1.", FastPgnParser.moveNum(using _))
      r.isSuccess shouldBe true
    }

    it("moveNum should parse black move indicator '1...'") {
      import fastparse.*
      val r = fastparse.parse("1...", FastPgnParser.moveNum(using _))
      r.isSuccess shouldBe true
    }

    it("moveNum should parse multi-digit move numbers like '20.'") {
      import fastparse.*
      val r = fastparse.parse("20.", FastPgnParser.moveNum(using _))
      r.isSuccess shouldBe true
    }

    it("comment should parse '{this is a comment}'") {
      import fastparse.*
      val r = fastparse.parse("{this is a comment}", FastPgnParser.comment(using _))
      r.isSuccess shouldBe true
      r.get.value should include("comment")
    }

    it("sanChar should accept valid SAN characters") {
      import fastparse.*
      for ch <- "abcdefgh12345678NBRQKx=+#-" do
        val r = fastparse.parse(ch.toString, FastPgnParser.sanChar(using _))
        withClue(s"sanChar should accept '$ch'") {
          r.isSuccess shouldBe true
        }
    }

    it("san should parse a simple pawn move") {
      import fastparse.*
      val r = fastparse.parse("e4", FastPgnParser.san(using _))
      r.isSuccess shouldBe true
      r.get.value shouldBe "e4"
    }

    it("san should parse 'O-O' (kingside castling)") {
      import fastparse.*
      val r = fastparse.parse("O-O", FastPgnParser.san(using _))
      r.isSuccess shouldBe true
      r.get.value shouldBe "O-O"
    }

    it("san should parse 'O-O-O' (queenside castling)") {
      import fastparse.*
      val r = fastparse.parse("O-O-O", FastPgnParser.san(using _))
      r.isSuccess shouldBe true
      r.get.value shouldBe "O-O-O"
    }

    it("result should parse all four terminal tokens") {
      import fastparse.*
      for res <- Seq("1-0", "0-1", "1/2-1/2", "*") do
        val r = fastparse.parse(res, FastPgnParser.result(using _))
        withClue(s"result should accept '$res'") {
          r.isSuccess shouldBe true
          r.get.value shouldBe res
        }
    }
  }

  // ─── pgn() – Haupt-Grammatikregel/Filterlogik ────────────────────────────────

  describe("FastPgnParser.pgn grammar rule (filtering logic)") {

    it("should exclude move numbers from the move list (. filter)") {
      // pgn().map filters out strings containing '.'
      val result = FastPgnParser.parse("1. e4 e5")
      result.isSuccess shouldBe true
      // The filtered list should give us two actual moves
      result.get.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn)) // e4
      result.get.board.get(Pos(4, 4)) shouldBe Some(Piece(Color.Black, PieceType.Pawn)) // e5
    }

    it("should exclude result tokens from the move list ('*' filter and '-' filter)") {
      val result = FastPgnParser.parse("1. e4 1-0")
      result.isSuccess shouldBe true
      // Only one move was played (e4), the result token must be filtered out
      result.get.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    }

    it("should exclude comments from the move list ('{' filter)") {
      val result = FastPgnParser.parse("1. e4 {good move} e5")
      result.isSuccess shouldBe true
      result.get.board.get(Pos(4, 4)) shouldBe Some(Piece(Color.Black, PieceType.Pawn)) // e5
    }

    it("should keep 'O-O' castling moves (passes the '-' filter exception)") {
      // This exercises the second `case` branch: s == "O-O" || s == "O-O-O"
      val result = FastPgnParser.parse("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. O-O")
      result.isSuccess shouldBe true
    }

    it("should keep 'O-O-O' queenside castling moves") {
      // A position where Black can castle queenside legally:
      // After some moves that clear the way for Black's queenside castling
      // Simplest valid PGN ending in O-O-O for Black:
      // 1.d4 d5 2.c4 e6 3.Nc3 Nf6 4.Nf3 Be7 5.Bg5 O-O (White O-O after Rook+Bishop cleared)
      // Actually: let's use a white queenside castle scenario:
      // 1. d4 d5 2. Nc3 Nc6 3. Bf4 Bf5 4. Qd2 Qd7 5. O-O-O
      val pgn = "1. d4 d5 2. Nc3 Nc6 3. Bf4 Bf5 4. Qd2 Qd7 5. O-O-O"
      val result = FastPgnParser.parse(pgn)
      result.isSuccess shouldBe true
    }

    it("pgn collect handles ws-only PGN (empty moves)") {
      // Just a result token - results in empty move list
      val result = FastPgnParser.parse("*")
      result.isSuccess shouldBe true
      result.get.board shouldBe GameState.initial.board
    }

    it("pgn should fail for fastparse failure on totally invalid syntax") {
      // Input that can't match at all: '@@@' isn't a valid token
      val result = FastPgnParser.parse("@@@")
      result.isFailure shouldBe true
      result.failed.get.getMessage should include("Fastparse failure")
    }

    it("pgn should fail with exception for illegal chess move token") {
      // Syntactically valid but chess-illegal: causes exception in move loop
      val result = FastPgnParser.parse("1. e5")
      result.isFailure shouldBe true
      result.failed.get.getMessage should include("Illegal move")
    }
  }

  // ─── Full integration games using FastPgnParser ──────────────────────────────

  describe("FastPgnParser full game integration") {

    it("should parse a PGN with header tags and result (ws between tags)") {
      val pgn =
        """[Event "Test"]
          |[White "Alice"]
          |
          |1. e4 e5 2. Nf3 1-0""".stripMargin
      val result = FastPgnParser.parse(pgn)
      result.isSuccess shouldBe true
    }

    it("should parse a game with both O-O and move number on same line") {
      val pgn = "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. O-O Nf6 5. Re1"
      // 4. O-O exercises the O-O filter path
      val result = FastPgnParser.parse(pgn)
      result.isSuccess shouldBe true
    }

    it("should parse a game with capture moves (x character via sanChar)") {
      val pgn = "1. e4 d5 2. exd5"
      val result = FastPgnParser.parse(pgn)
      result.isSuccess shouldBe true
    }

    it("should handle ws before first tag") {
      val pgn = "   [Event \"E\"]   1. e4  "
      val result = FastPgnParser.parse(pgn)
      result.isSuccess shouldBe true
    }
  }
}
