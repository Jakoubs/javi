package chess

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import chess.model.*
import chess.controller.*
import chess.view.TerminalView

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
    app = GameController.handleCommand(app, Command.MakeMove(move1))
    app.game.activeColor shouldBe Color.Black
    app.lastMove shouldBe Some(move1)
    app.message shouldBe None

    val move2 = Move(Pos(4, 6), Pos(4, 4))
    app = GameController.handleCommand(app, Command.MakeMove(move2))
    app.game.activeColor shouldBe Color.White
    app.lastMove shouldBe Some(move2)
    app.message shouldBe None
  }

  test("Command parser should handle all commands") {
    CommandParser.parse("e2e4") shouldBe a[Command.MakeMove]
    CommandParser.parse("e7e5") shouldBe a[Command.MakeMove]
    CommandParser.parse("e7e8q") shouldBe a[Command.MakeMove]

    CommandParser.parse("flip") shouldBe Command.Flip
    CommandParser.parse("undo") shouldBe Command.Undo
    CommandParser.parse("resign") shouldBe Command.Resign
    CommandParser.parse("draw") shouldBe Command.OfferDraw
    CommandParser.parse("new") shouldBe Command.NewGame
    CommandParser.parse("help") shouldBe Command.Help
    CommandParser.parse("quit") shouldBe Command.Quit

    CommandParser.parse("moves e2") shouldBe a[Command.ShowMoves]

    CommandParser.parse("invalid") shouldBe a[Command.Unknown]
    CommandParser.parse("xyz") shouldBe a[Command.Unknown]
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
