package chess.controller

import chess.controller.{AppState, Command}
import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class CommandParserSpec extends AnyFunSpec with Matchers {
  val initialApp = AppState.initial

  describe("CommandParser.parse") {
    
    describe("Simple keywords") {
      it("should parse 'flip'") {
        CommandParser.parse("flip", initialApp) shouldBe Command.Flip
      }
      it("should parse 'undo'") {
        CommandParser.parse("undo", initialApp) shouldBe Command.Undo
      }
      it("should parse 'resign'") {
        CommandParser.parse("resign", initialApp) shouldBe Command.Resign
      }
      it("should parse 'draw'") {
        CommandParser.parse("draw", initialApp) shouldBe Command.OfferDraw
      }
      it("should parse 'new' and 'newgame'") {
        CommandParser.parse("new", initialApp) shouldBe Command.NewGame
        CommandParser.parse("newgame", initialApp) shouldBe Command.NewGame
      }
      it("should parse 'help' and '?'") {
        CommandParser.parse("help", initialApp) shouldBe Command.Help
        CommandParser.parse("?", initialApp) shouldBe Command.Help
      }
      it("should parse 'quit', 'exit', 'q'") {
        CommandParser.parse("quit", initialApp) shouldBe Command.Quit
        CommandParser.parse("exit", initialApp) shouldBe Command.Quit
        CommandParser.parse("q", initialApp) shouldBe Command.Quit
      }
      it("should parse 'pgn'") {
        CommandParser.parse("pgn", initialApp) shouldBe Command.ShowPgn
      }
      it("should parse 'fen'") {
        CommandParser.parse("fen", initialApp) shouldBe Command.ShowFen
      }
    }

    describe("AI commands") {
      it("should parse 'ai' as AiMove") {
        CommandParser.parse("ai", initialApp) shouldBe Command.AiMove
      }
      it("should parse 'ai w' and 'ai white'") {
        CommandParser.parse("ai w", initialApp) shouldBe Command.ToggleAi(Color.White)
        CommandParser.parse("ai white", initialApp) shouldBe Command.ToggleAi(Color.White)
      }
      it("should parse 'ai b' and 'ai black'") {
        CommandParser.parse("ai b", initialApp) shouldBe Command.ToggleAi(Color.Black)
        CommandParser.parse("ai black", initialApp) shouldBe Command.ToggleAi(Color.Black)
      }
      it("should parse 'train [n]'") {
        CommandParser.parse("train 100", initialApp) shouldBe Command.AiTrain(100)
      }
      it("should return Unknown for invalid train count") {
        CommandParser.parse("train abc", initialApp) shouldBe a [Command.Unknown]
      }
    }

    describe("Moves and Highlighting") {
      it("should parse 'moves [pos]'") {
        CommandParser.parse("moves e2", initialApp) shouldBe Command.SelectSquare(Some(Pos(4, 1)))
      }
      it("should return Unknown for invalid moves position") {
        CommandParser.parse("moves z9", initialApp) shouldBe a [Command.Unknown]
      }
      it("should parse algebraic moves (coordinate by default)") {
        // e2e4 is valid coordinate
        CommandParser.parse("e2e4", initialApp) shouldBe Command.ApplyMove(Move(Pos(4,1), Pos(4,3)))
      }
      it("should parse promotion moves") {
        CommandParser.parse("e7e8q", initialApp) shouldBe Command.ApplyMove(Move(Pos(4,6), Pos(4,7), Some(PieceType.Queen)))
      }
    }

    describe("Parser switching") {
      it("should parse 'parser pgn fast'") {
        CommandParser.parse("parser pgn fast", initialApp) shouldBe Command.SwitchParser("pgn", "fast")
      }
      it("should parse 'parser move san'") {
        CommandParser.parse("parser move san", initialApp) shouldBe Command.SwitchParser("move", "san")
      }
      it("should return Unknown for malformed parser command") {
        CommandParser.parse("parser onlyone", initialApp) shouldBe a [Command.Unknown]
      }
    }

    describe("Edge cases") {
      it("should be case-insensitive") {
        CommandParser.parse("FLIP", initialApp) shouldBe Command.Flip
        CommandParser.parse("Undo", initialApp) shouldBe Command.Undo
      }
      it("should trim whitespace") {
        CommandParser.parse("  undo  ", initialApp) shouldBe Command.Undo
      }
      it("should return Unknown for empty/garbage string") {
        CommandParser.parse("", initialApp) shouldBe a [Command.Unknown]
        CommandParser.parse("blablabla", initialApp) shouldBe a [Command.Unknown]
      }
    }
  }
}
