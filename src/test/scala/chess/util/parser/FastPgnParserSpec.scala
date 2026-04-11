package chess.util.parser

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import fastparse.*

class FastPgnParserSpec extends AnyFunSpec with Matchers {

  describe("Grammar rules") {

    it("ws / ws1") {
      fastparse.parse(" \n", FastPgnParser.ws(using _)).isSuccess shouldBe true
      fastparse.parse("", FastPgnParser.ws1(using _)).isSuccess shouldBe false
    }

    it("tag / tags") {
      fastparse.parse("[Event \"Test\"]", FastPgnParser.tag(using _)).isSuccess shouldBe true
      fastparse.parse("[A \"B\"] [C \"D\"]", FastPgnParser.tags(using _)).isSuccess shouldBe true
    }

    it("moveNum") {
      fastparse.parse("1.", FastPgnParser.moveNum(using _)).isSuccess shouldBe true
      fastparse.parse("1", FastPgnParser.moveNum(using _)).isSuccess shouldBe false
    }

    it("comment") {
      fastparse.parse("{hi}", FastPgnParser.comment(using _)).isSuccess shouldBe true
      fastparse.parse("{oops", FastPgnParser.comment(using _)).isSuccess shouldBe false
    }

    it("san") {
      fastparse.parse("e4", FastPgnParser.san(using _)).isSuccess shouldBe true
      fastparse.parse("O-O", FastPgnParser.san(using _)).isSuccess shouldBe true
      fastparse.parse("@@@", FastPgnParser.san(using _)).isSuccess shouldBe false
    }

    it("result") {
      fastparse.parse("1-0", FastPgnParser.result(using _)).isSuccess shouldBe true
      fastparse.parse("2-0", FastPgnParser.result(using _)).isSuccess shouldBe false
    }

    it("nag") {
      fastparse.parse("$1", FastPgnParser.nag(using _)).isSuccess shouldBe true
      fastparse.parse("$", FastPgnParser.nag(using _)).isSuccess shouldBe false
    }
  }

  describe("extractMoves") {

    it("should keep normal moves") {
      FastPgnParser.extractMoves(Seq("e4", "e5")) shouldBe List("e4", "e5")
    }

    it("should filter move numbers") {
      FastPgnParser.extractMoves(Seq("1.", "e4")) shouldBe List("e4")
    }

    it("should filter comments") {
      FastPgnParser.extractMoves(Seq("{c}", "e4")) shouldBe List("e4")
    }

    it("should filter nags") {
      FastPgnParser.extractMoves(Seq("$1", "e4")) shouldBe List("e4")
    }

    it("should filter results") {
      FastPgnParser.extractMoves(Seq("1-0", "e4")) shouldBe List("e4")
    }

    it("should ignore '*'") {
      FastPgnParser.extractMoves(Seq("*", "e4")) shouldBe List("e4")
    }

    it("should keep castling") {
      FastPgnParser.extractMoves(Seq("O-O", "O-O-O")) shouldBe List("O-O", "O-O-O")
    }
  }

  describe("pgn rule") {

    it("should extract moves correctly") {
      val Parsed.Success(moves, _) =
        fastparse.parse("""[E "T"] 1. e4 {c} $1 e5 1-0""", FastPgnParser.pgn(using _)) : @unchecked

      moves shouldBe List("e4", "e5")
    }

    it("should handle castling") {
      val Parsed.Success(moves, _) =
        fastparse.parse("1. O-O O-O-O", FastPgnParser.pgn(using _)) : @unchecked

      moves shouldBe List("O-O", "O-O-O")
    }
  }

  describe("parse integration") {

    it("should parse a valid game") {
      val res = FastPgnParser.parse("1. e4 e5 2. Nf3 Nc6")
      res.isSuccess shouldBe true
      res.get.history.size shouldBe 4
    }

    it("should handle + and #") {
      val res = FastPgnParser.parse("1. e4 e5 2. Qh5+ Nc6 3. Qxf7#")
      res.isSuccess shouldBe true
    }

    it("should fail on illegal move") {
      FastPgnParser.parse("1. e4 e5 2. Qa9").isFailure shouldBe true
    }

    it("should fail on syntax error") {
      FastPgnParser.parse("@@@").isFailure shouldBe true
    }

    it("should handle empty input") {
      val res = FastPgnParser.parse("")
      res.isSuccess shouldBe true
      res.get.history.size shouldBe 0
    }
  }
}