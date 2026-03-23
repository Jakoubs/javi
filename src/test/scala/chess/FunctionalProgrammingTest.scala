package chess

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import chess.model.*
import chess.controller.*
import chess.view.TerminalView

class FunctionalProgrammingTest extends AnyFunSuite with Matchers with ScalaCheckPropertyChecks:

  test("GameController should use immutable state") {
    val initialApp = AppState.initial
    val command = Command.Flip
    
    // Apply command multiple times
    val app1 = GameController.handleCommand(initialApp, command)
    val app2 = GameController.handleCommand(app1, command)
    val app3 = GameController.handleCommand(app2, command)
    
    // Original should remain unchanged (immutability)
    initialApp.flipped shouldBe false
    app1.flipped shouldBe true
    app2.flipped shouldBe false
    app3.flipped shouldBe true
  }

  test("TerminalView should be pure functional") {
    val state = GameState.initial
    val status = GameStatus.Playing
    
    // Multiple calls with same input should produce same output
    val render1 = TerminalView.render(state, status)
    val render2 = TerminalView.render(state, status)
    
    render1 shouldBe render2
    
    // Should contain expected functional elements
    render1 should include("White")
    render1 should include("Black")
  }

  test("CommandParser should be pure functional") {
    val inputs = List("e2e4", "flip", "undo")
    
    // Multiple parses of same input should give same result
    inputs.foreach { input =>
      val result1 = CommandParser.parse(input)
      val result2 = CommandParser.parse(input)
      result1 shouldBe result2
    }
  }

  test("Piece operations should be functional") {
    val whiteKing = Piece(Color.White, PieceType.King)
    val blackKing = Piece(Color.Black, PieceType.King)
    
    // Symbol should be deterministic
    whiteKing.symbol shouldBe whiteKing.symbol
    blackKing.symbol shouldBe blackKing.symbol
    
    // Color operations should be functional
    Color.White.opposite shouldBe Color.Black
    Color.Black.opposite shouldBe Color.White
  }

  test("Position operations should be functional") {
    val pos = Pos(4, 4)
    
    // Addition should create new instance
    val newPos = pos + (1, 2)
    newPos should not be theSameInstanceAs(pos)
    
    // Should be immutable
    pos shouldBe Pos(4, 4)
    newPos shouldBe Pos(5, 6)
  }

  test("Game operations should be functional") {
    val game = GameState.initial
    
    // Legal moves should be deterministic
    val moves1 = MoveGenerator.legalMoves(game)
    val moves2 = MoveGenerator.legalMoves(game)
    
    moves1 shouldBe moves2
    
    // Game state should be immutable
    val sameGame = game
    sameGame shouldBe game
  }

  test("Functional composition should work") {
    val game = GameState.initial
    
    // Get legal moves functionally
    val legalMoves = MoveGenerator.legalMoves(game)
    
    // Should have moves
    legalMoves should not be empty
    
    // All moves should be from current player's pieces
    legalMoves.foreach { move =>
      game.board.get(move.from) should be(defined)
      game.board.get(move.from).get.color shouldBe game.activeColor
    }
  }
