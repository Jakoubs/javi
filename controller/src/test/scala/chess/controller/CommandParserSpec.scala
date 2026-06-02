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
      it("should parse history navigation keywords") {
        CommandParser.parse("back", initialApp) shouldBe Command.StepBack
        CommandParser.parse("forward", initialApp) shouldBe Command.StepForward
        CommandParser.parse("first", initialApp) shouldBe Command.FirstHistory
        CommandParser.parse("last", initialApp) shouldBe Command.LastHistory
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
      it("should parse 'bot <name>'") {
        CommandParser.parse("bot alphabeta", initialApp) shouldBe Command.SetAiBot("alphabeta")
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
      it("should parse square-only selections") {
        CommandParser.parse("e2", initialApp) shouldBe Command.SelectSquare(Some(Pos(4, 1)))
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

    describe("Loading and clock commands") {
      it("should parse load commands") {
        CommandParser.parse("load pgn 1. e4 e5", initialApp) shouldBe Command.LoadPgn("1. e4 e5")
        CommandParser.parse("load fen 8/8/8/8/8/8/8/4K3 w - - 0 1", initialApp) shouldBe
          Command.LoadFen("8/8/8/8/8/8/8/4K3 w - - 0 1")
      }

      it("should parse preset and unlimited start commands") {
        CommandParser.parse("start none", initialApp) shouldBe Command.StartGame(None)
        CommandParser.parse("start unlimited", initialApp) shouldBe Command.StartGame(None)
        CommandParser.parse("start bullet", initialApp) shouldBe Command.StartGame(Some(60000L, 0L))
        CommandParser.parse("time blitz", initialApp) shouldBe Command.StartGame(Some(180000L, 2000L))
        CommandParser.parse("start rapid", initialApp) shouldBe Command.StartGame(Some(600000L, 0L))
      }

      it("should parse explicit clock values and reject malformed ones") {
        CommandParser.parse("start 60000 1000", initialApp) shouldBe Command.StartGame(Some(60000L, 1000L))
        CommandParser.parse("start 60000 x", initialApp) shouldBe a [Command.Unknown]
        CommandParser.parse("start 60000", initialApp) shouldBe a [Command.Unknown]
      }
    }

    describe("Jump and parser fallback") {
      it("should parse jump commands") {
        CommandParser.parse("jump 3", initialApp) shouldBe Command.JumpToHistory(3)
        CommandParser.parse("jump nope", initialApp) shouldBe a [Command.Unknown]
      }

      it("should fall back to coordinate parsing when the active parser is SAN") {
        val sanApp = initialApp.copy(activeMoveParser = "san")
        CommandParser.parse("e2e4", sanApp) shouldBe Command.ApplyMove(Move(Pos(4, 1), Pos(4, 3)))
      }

      it("should return move-specific errors when SAN is active") {
        val sanApp = initialApp.copy(activeMoveParser = "san")
        CommandParser.parse("zz", sanApp) shouldBe Command.Unknown("Invalid move format: zz")
      }

      it("should return command-style errors for short invalid tokens with the coordinate parser") {
        CommandParser.parse("zz", initialApp) shouldBe Command.Unknown("Unknown command or invalid move: zz")
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
