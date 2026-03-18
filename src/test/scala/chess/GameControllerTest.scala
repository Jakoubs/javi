package chess

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import chess.model.*
import chess.controller.*

class CommandParserTest extends AnyFunSuite with Matchers:

  test("parses a simple move") {
    CommandParser.parse("e2e4") shouldBe Command.MakeMove(Move(Pos(4,1), Pos(4,3)))
  }

  test("parses a move with promotion") {
    CommandParser.parse("e7e8q") shouldBe
      Command.MakeMove(Move(Pos(4,6), Pos(4,7), Some(PieceType.Queen)))
  }

  test("parses 'moves e2'") {
    CommandParser.parse("moves e2") shouldBe Command.ShowMoves(Pos(4,1))
  }

  test("parses 'flip'") {
    CommandParser.parse("flip") shouldBe Command.Flip
  }

  test("parses 'undo'") {
    CommandParser.parse("undo") shouldBe Command.Undo
  }

  test("parses 'resign'") {
    CommandParser.parse("resign") shouldBe Command.Resign
  }

  test("parses 'new'") {
    CommandParser.parse("new") shouldBe Command.NewGame
  }

  test("parses 'quit'") {
    CommandParser.parse("quit") shouldBe Command.Quit
  }

  test("unknown input produces Unknown command") {
    CommandParser.parse("banana") match
      case Command.Unknown(_) => succeed
      case other              => fail(s"Expected Unknown, got $other")
  }

  test("invalid position in 'moves' produces Unknown") {
    CommandParser.parse("moves z9") match
      case Command.Unknown(_) => succeed
      case other              => fail(s"Expected Unknown, got $other")
  }

class GameControllerTest extends AnyFunSuite with Matchers:

  def pos(s: String): Pos = Pos.fromAlgebraic(s).get

  test("initial app state is Playing") {
    val app = AppState.initial
    app.status shouldBe GameStatus.Playing
    app.game.activeColor shouldBe Color.White
  }

  test("legal move updates board and switches active color") {
    val app  = AppState.initial
    val cmd  = Command.MakeMove(Move(pos("e2"), pos("e4")))
    val next = GameController.handleCommand(app, cmd)
    next.game.board.get(pos("e4")) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    next.game.board.get(pos("e2")) shouldBe None
    next.game.activeColor shouldBe Color.Black
  }

  test("illegal move produces an error message and does not change board") {
    val app  = AppState.initial
    val cmd  = Command.MakeMove(Move(pos("e2"), pos("e5")))  // pawn can't jump
    val next = GameController.handleCommand(app, cmd)
    next.game shouldBe app.game
    next.message.exists(_.contains("Illegal")) shouldBe true
  }

  test("undo after first move returns to initial board") {
    val app   = AppState.initial
    val after = GameController.handleCommand(app, Command.MakeMove(Move(pos("e2"), pos("e4"))))
    val undo  = GameController.handleCommand(after, Command.Undo)
    undo.game.board shouldBe app.game.board
    undo.game.activeColor shouldBe Color.White
  }

  test("undo on empty history shows error") {
    val app  = AppState.initial
    val next = GameController.handleCommand(app, Command.Undo)
    next.message.exists(_.contains("Nothing")) shouldBe true
  }

  test("ShowMoves highlights targets for a valid own piece") {
    val app  = AppState.initial
    val next = GameController.handleCommand(app, Command.ShowMoves(pos("e2")))
    next.highlights should contain(pos("e3"))
    next.highlights should contain(pos("e4"))
  }

  test("ShowMoves on empty square shows error") {
    val app  = AppState.initial
    val next = GameController.handleCommand(app, Command.ShowMoves(pos("e5")))
    next.message.exists(_.contains("No piece")) shouldBe true
  }

  test("Flip toggles flipped flag") {
    val app  = AppState.initial
    val next = GameController.handleCommand(app, Command.Flip)
    next.flipped shouldBe true
    val back = GameController.handleCommand(next, Command.Flip)
    back.flipped shouldBe false
  }

  test("NewGame resets the game state") {
    val app   = AppState.initial
    val moved = GameController.handleCommand(app, Command.MakeMove(Move(pos("e2"), pos("e4"))))
    val fresh = GameController.handleCommand(moved, Command.NewGame)
    fresh.game.board shouldBe Board.initial
    fresh.game.activeColor shouldBe Color.White
  }

  test("Quit sets running to false") {
    val app  = AppState.initial
    val next = GameController.handleCommand(app, Command.Quit)
    next.running shouldBe false
  }

  test("draw offer then accept produces Draw status") {
    val app     = AppState.initial
    val offered = GameController.handleCommand(app, Command.OfferDraw)
    offered.drawOffer shouldBe true
    val accepted = GameController.handleCommand(offered, Command.OfferDraw)
    accepted.status shouldBe GameStatus.Draw("agreement")
  }
