package chess.controller

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import chess.model.{Color, Pos, GameState, Board, CastlingRights, WhiteToMove}

class CommandParserSpec extends AnyWordSpec with Matchers {

  "CommandParser" should {
    
    val defaultApp = AppState.initial

    "parse basic commands" in {
      CommandParser.parse("flip", defaultApp) shouldBe Command.Flip
      CommandParser.parse("undo", defaultApp) shouldBe Command.Undo
      CommandParser.parse("resign", defaultApp) shouldBe Command.Resign
      CommandParser.parse("draw", defaultApp) shouldBe Command.OfferDraw
      CommandParser.parse("new", defaultApp) shouldBe Command.NewGame
      CommandParser.parse("newgame", defaultApp) shouldBe Command.NewGame
      CommandParser.parse("help", defaultApp) shouldBe Command.Help
      CommandParser.parse("?", defaultApp) shouldBe Command.Help
      CommandParser.parse("quit", defaultApp) shouldBe Command.Quit
      CommandParser.parse("exit", defaultApp) shouldBe Command.Quit
      CommandParser.parse("q", defaultApp) shouldBe Command.Quit
      CommandParser.parse("pgn", defaultApp) shouldBe Command.ShowPgn
      CommandParser.parse("fen", defaultApp) shouldBe Command.ShowFen
      CommandParser.parse("back", defaultApp) shouldBe Command.StepBack
      CommandParser.parse("forward", defaultApp) shouldBe Command.StepForward
      CommandParser.parse("first", defaultApp) shouldBe Command.FirstHistory
      CommandParser.parse("last", defaultApp) shouldBe Command.LastHistory
    }

    "parse ai commands" in {
      CommandParser.parse("ai", defaultApp) shouldBe Command.AiMove
      CommandParser.parse("ai w", defaultApp) shouldBe Command.ToggleAi(Color.White)
      CommandParser.parse("ai white", defaultApp) shouldBe Command.ToggleAi(Color.White)
      CommandParser.parse("ai b", defaultApp) shouldBe Command.ToggleAi(Color.Black)
      CommandParser.parse("ai black", defaultApp) shouldBe Command.ToggleAi(Color.Black)
    }

    "parse bot command" in {
      CommandParser.parse("bot stockfish", defaultApp) shouldBe Command.SetAiBot("stockfish")
    }

    "parse load and start commands" in {
      CommandParser.parse("load pgn 1. e4", defaultApp) shouldBe Command.LoadPgn("1. e4")
      CommandParser.parse("load fen start", defaultApp) shouldBe Command.LoadFen("start")
      
      CommandParser.parse("start none", defaultApp) shouldBe Command.StartGame(None)
      CommandParser.parse("time unlimited", defaultApp) shouldBe Command.StartGame(None)
      CommandParser.parse("start bullet", defaultApp) shouldBe Command.StartGame(Some(60000L, 0L))
      CommandParser.parse("start blitz", defaultApp) shouldBe Command.StartGame(Some(180000L, 2000L))
      CommandParser.parse("start rapid", defaultApp) shouldBe Command.StartGame(Some(600000L, 0L))
      CommandParser.parse("start 1000 500", defaultApp) shouldBe Command.StartGame(Some(1000L, 500L))
      CommandParser.parse("start invalid", defaultApp).isInstanceOf[Command.Unknown] shouldBe true
      CommandParser.parse("start 1000 invalid", defaultApp).isInstanceOf[Command.Unknown] shouldBe true
    }

    "parse moves and squares commands" in {
       CommandParser.parse("moves e2", defaultApp) shouldBe Command.SelectSquare(Some(Pos(4, 1))) // x=4, y=1 is e2
       CommandParser.parse("moves invalid", defaultApp).isInstanceOf[Command.Unknown] shouldBe true
    }

    "parse training and jump commands" in {
       CommandParser.parse("train 10", defaultApp) shouldBe Command.AiTrain(10)
       CommandParser.parse("train invalid", defaultApp).isInstanceOf[Command.Unknown] shouldBe true
       
       CommandParser.parse("jump 5", defaultApp) shouldBe Command.JumpToHistory(5)
       CommandParser.parse("jump invalid", defaultApp).isInstanceOf[Command.Unknown] shouldBe true
    }

    "parse parser commands" in {
       CommandParser.parse("parser pgn fast", defaultApp) shouldBe Command.SwitchParser("pgn", "fast")
       CommandParser.parse("parser invalid", defaultApp).isInstanceOf[Command.Unknown] shouldBe true
    }

    "parse simple moves or selections" in {
       CommandParser.parse("e2", defaultApp) shouldBe Command.SelectSquare(Some(Pos(4, 1)))
       
       // Default active parser is "coordinate" for moves if not set to san
       val moveApp = AppState.initial 
       val moveCmd = CommandParser.parse("e2e4", moveApp)
       moveCmd.isInstanceOf[Command.ApplyMove] shouldBe true
       
       // Test unknown command
       CommandParser.parse("e2e9", moveApp).isInstanceOf[Command.Unknown] shouldBe true
       CommandParser.parse("xyz", moveApp).isInstanceOf[Command.Unknown] shouldBe true
       
       val sanApp = AppState.initial.copy(activeMoveParser = "san")
       CommandParser.parse("xyz", sanApp).isInstanceOf[Command.Unknown] shouldBe true
    }
  }
}
