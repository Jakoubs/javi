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
  }

  test("Initial board should have pieces in correct positions") {
    val board = GameState.initial.board
    
    // White pieces
    board.get(Pos(4, 0)) shouldBe Some(Piece(Color.White, PieceType.King))
    board.get(Pos(3, 0)) shouldBe Some(Piece(Color.White, PieceType.Queen))
    board.get(Pos(0, 0)) shouldBe Some(Piece(Color.White, PieceType.Rook))
    board.get(Pos(7, 0)) shouldBe Some(Piece(Color.White, PieceType.Rook))
    
    // White pawns
    for col <- 0 to 7 do
      board.get(Pos(col, 1)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    
    // Black pieces
    board.get(Pos(4, 7)) shouldBe Some(Piece(Color.Black, PieceType.King))
    board.get(Pos(3, 7)) shouldBe Some(Piece(Color.Black, PieceType.Queen))
    board.get(Pos(0, 7)) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    board.get(Pos(7, 7)) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    
    // Black pawns
    for col <- 0 to 7 do
      board.get(Pos(col, 6)) shouldBe Some(Piece(Color.Black, PieceType.Pawn))
  }

  test("Game status should be Playing initially") {
    val game = GameState.initial
    val status = GameRules.computeStatus(game)
    status shouldBe GameStatus.Playing
  }

  test("App should handle basic move sequence") {
    var app = AppState.initial
    
    // First move: e2e4
    val move1 = Move(Pos(4, 1), Pos(4, 3))
    app = GameController.handleCommand(app, Command.MakeMove(move1))
    app.game.activeColor shouldBe Color.Black
    app.lastMove shouldBe Some(move1)
    app.message shouldBe None
    
    // Second move: e7e5
    val move2 = Move(Pos(4, 6), Pos(4, 4))
    app = GameController.handleCommand(app, Command.MakeMove(move2))
    app.game.activeColor shouldBe Color.White
    app.lastMove shouldBe Some(move2)
    app.message shouldBe None
  }

  test("Command parser should handle all commands") {
    // Simple moves
    CommandParser.parse("e2e4") shouldBe a[Command.MakeMove]
    CommandParser.parse("e7e5") shouldBe a[Command.MakeMove]
    
    // Moves with promotion
    CommandParser.parse("e7e8q") shouldBe a[Command.MakeMove]
    
    // Special commands
    CommandParser.parse("flip") shouldBe Command.Flip
    CommandParser.parse("undo") shouldBe Command.Undo
    CommandParser.parse("resign") shouldBe Command.Resign
    CommandParser.parse("draw") shouldBe Command.OfferDraw
    CommandParser.parse("new") shouldBe Command.NewGame
    CommandParser.parse("help") shouldBe Command.Help
    CommandParser.parse("quit") shouldBe Command.Quit
    
    // Show moves
    CommandParser.parse("moves e2") shouldBe a[Command.ShowMoves]
    
    // Unknown commands
    CommandParser.parse("invalid") shouldBe a[Command.Unknown]
    CommandParser.parse("xyz") shouldBe a[Command.Unknown]
  }

  test("TerminalView should render without errors") {
    val state = GameState.initial
    val status = GameStatus.Playing
    
    val rendered = TerminalView.render(state, status)
    rendered should include("White")
    rendered should include("Black")
    rendered should include("♔") // White king
    rendered should include("♚") // Black king
    rendered should include("♙") // White pawn
    rendered should include("♟") // Black pawn
  }
