package chess

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import chess.model.*
import chess.controller.*
import chess.view.{TerminalView, CommandParser}
import chess.util.parser.MoveParser

class IntegrationTest extends AnyFunSuite with Matchers:

  test("Game should start with correct initial state") {
    val gameState = GameState.initial
    gameState.activeColor shouldBe Color.White
    gameState.fullMoveNumber shouldBe 1
    gameState.halfMoveClock shouldBe 0
    gameState.toFen shouldBe GameState.initialFen
  }

  test("Initial FEN should parse back to the same game state") {
    val parsed = GameState.fromFen(GameState.initialFen).fold(msg => fail(msg), identity)
    parsed shouldBe GameState.initial
  }

  test("Game status should be Playing initially") {
    val game = GameState.initial
    val status = GameRules.computeStatus(game)
    status shouldBe GameStatus.Playing
  }

  test("App should handle basic move sequence") {
    var app = AppState.initial

    val move1 = Move(Pos(4, 1), Pos(4, 3))
    app = GameController.handleCommand(app, Command.ApplyMove(move1))
    app.game.activeColor shouldBe Color.Black
    app.lastMove shouldBe Some(move1)
    app.message shouldBe None

    val move2 = Move(Pos(4, 6), Pos(4, 4))
    app = GameController.handleCommand(app, Command.ApplyMove(move2))
    app.game.activeColor shouldBe Color.White
    app.lastMove shouldBe Some(move2)
    app.message shouldBe None
  }

  test("Command parser should handle all commands") {
    CommandParser.parse("e2e4", AppState.initial) shouldBe a[Command.ApplyMove]
    CommandParser.parse("e7e5", AppState.initial) shouldBe a[Command.ApplyMove]
    CommandParser.parse("e7e8q", AppState.initial) shouldBe a[Command.ApplyMove]

    CommandParser.parse("flip", AppState.initial) shouldBe Command.Flip
    CommandParser.parse("undo", AppState.initial) shouldBe Command.Undo
    CommandParser.parse("resign", AppState.initial) shouldBe Command.Resign
    CommandParser.parse("draw", AppState.initial) shouldBe Command.OfferDraw
    CommandParser.parse("new", AppState.initial) shouldBe Command.NewGame
    CommandParser.parse("help", AppState.initial) shouldBe Command.Help
    CommandParser.parse("quit", AppState.initial) shouldBe Command.Quit

    CommandParser.parse("moves e2", AppState.initial) shouldBe a[Command.SelectSquare]

    CommandParser.parse("invalid", AppState.initial) shouldBe a[Command.Unknown]
    CommandParser.parse("xyz", AppState.initial) shouldBe a[Command.Unknown]
  }

  test("TerminalView should render without errors") {
    val state = GameState.initial
    val status = GameStatus.Playing

    val rendered = TerminalView.render(state, status)
    rendered should include("White")
    rendered should include("Black")
    rendered should include("♚")
    rendered should include("♛")
    rendered should include("♟")
  }
