package chess

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import chess.model.*
import chess.controller.*
import chess.view.{TerminalView, CommandParser}
import chess.util.parser.MoveParser
import scala.collection.mutable.ArrayBuffer

class CommandParserTest extends AnyFunSuite with Matchers:

  test("parses a simple move") {
    CommandParser.parse("e2e4", AppState.initial) shouldBe Command.ApplyMove(Move(Pos(4,1), Pos(4,3)))
  }

  test("parses a move with promotion") {
    CommandParser.parse("e7e8q", AppState.initial) shouldBe Command.ApplyMove(Move(Pos(4,6), Pos(4,7), Some(PieceType.Queen)))
    CommandParser.parse("e7e8r", AppState.initial) shouldBe Command.ApplyMove(Move(Pos(4,6), Pos(4,7), Some(PieceType.Rook)))
    CommandParser.parse("e7e8b", AppState.initial) shouldBe Command.ApplyMove(Move(Pos(4,6), Pos(4,7), Some(PieceType.Bishop)))
    CommandParser.parse("e7e8n", AppState.initial) shouldBe Command.ApplyMove(Move(Pos(4,6), Pos(4,7), Some(PieceType.Knight)))
  }

  test("parses 'moves e2'") {
    CommandParser.parse("moves e2", AppState.initial) shouldBe Command.SelectSquare(Some(Pos(4,1)))
  }

  test("parses 'flip'") {
    CommandParser.parse("flip", AppState.initial) shouldBe Command.Flip
  }

  test("parses 'undo'") {
    CommandParser.parse("undo", AppState.initial) shouldBe Command.Undo
  }

  test("parses 'resign'") {
    CommandParser.parse("resign", AppState.initial) shouldBe Command.Resign
  }

  test("parses 'new'") {
    CommandParser.parse("new", AppState.initial) shouldBe Command.NewGame
  }

  test("parses 'quit'") {
    CommandParser.parse("quit", AppState.initial) shouldBe Command.Quit
  }

  test("unknown input produces Unknown command") {
    CommandParser.parse("banana", AppState.initial) match
      case Command.Unknown(_) => succeed
      case other              => fail(s"Expected Unknown, got $other")
  }

  test("parses 'draw'") {
    CommandParser.parse("draw", AppState.initial) shouldBe Command.OfferDraw
  }

  test("parses 'help'") {
    CommandParser.parse("help", AppState.initial) shouldBe Command.Help
    CommandParser.parse("?", AppState.initial) shouldBe Command.Help
  }

  test("invalid short string produces Unknown") {
    CommandParser.parse("e2", AppState.initial) match
      case Command.Unknown(_) => succeed
      case other              => fail(s"Expected Unknown, got $other")
  }

  test("invalid characters in move string produces Unknown") {
    CommandParser.parse("z9z9", AppState.initial) match
      case Command.Unknown(_) => succeed
      case other              => fail(s"Expected Unknown, got $other")
  }

  test("invalid promotion char is ignored or produces Unknown") {
    // According to parseMove, if promoChar is defined but charToPromotion returns None, promo is None
    // However, length < 4 handled. If promo is empty and promoChar defined, it returns None -> Unknown
    CommandParser.parse("e7e8z", AppState.initial) match
      case Command.Unknown(_) => succeed
      case other              => fail(s"Expected Unknown, got $other")
  }

  test("invalid position in 'moves' produces Unknown") {
    CommandParser.parse("moves z9", AppState.initial) match
      case Command.Unknown(_) => succeed
      case other              => fail(s"Expected Unknown, got $other")
  }

class GameControllerTest extends AnyFunSuite with Matchers:

  private final class FakeConsole(inputs: List[String]) extends ConsoleIO:
    private val remaining = ArrayBuffer.from(inputs)
    val printed: ArrayBuffer[String] = ArrayBuffer.empty
    var clearCalls: Int = 0

    def readLine(): Option[String] =
      remaining.headOption.map { next =>
        remaining.remove(0)
        next
      }

    def print(text: String): Unit =
      printed += text

    def clear(): Unit =
      clearCalls += 1

  def pos(s: String): Pos = Pos.fromAlgebraic(s).get

  test("initial app state is Playing") {
    val app = AppState.initial
    app.status shouldBe GameStatus.Playing
    app.game.activeColor shouldBe Color.White
  }

  test("legal move updates board and switches active color") {
    val app  = AppState.initial
    val cmd  = Command.ApplyMove(Move(pos("e2"), pos("e4")))
    val next = GameController.handleCommand(app, cmd)
    next.game.board.get(pos("e4")) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    next.game.board.get(pos("e2")) shouldBe None
    next.game.activeColor shouldBe Color.Black
  }

  test("illegal move produces an error message and does not change board") {
    val app  = AppState.initial
    val cmd  = Command.ApplyMove(Move(pos("e2"), pos("e5")))  // pawn can't jump
    val next = GameController.handleCommand(app, cmd)
    next.game shouldBe app.game
    next.message.exists(_.contains("Illegal")) shouldBe true
  }

  test("undo after first move returns to initial board") {
    val app   = AppState.initial
    val after = GameController.handleCommand(app, Command.ApplyMove(Move(pos("e2"), pos("e4"))))
    val undo  = GameController.handleCommand(after, Command.Undo)
    undo.game.board shouldBe app.game.board
    undo.game.activeColor shouldBe Color.White
  }

  test("undo on empty history shows error") {
    val app  = AppState.initial
    val next = GameController.handleCommand(app, Command.Undo)
    next.message.exists(_.contains("Nothing")) shouldBe true
  }

  test("SelectSquare highlights targets for a valid own piece") {
    val app  = AppState.initial
    val next = GameController.handleCommand(app, Command.SelectSquare(Some(pos("e2"))))
    next.highlights should contain(pos("e3"))
    next.highlights should contain(pos("e4"))
  }

  test("SelectSquare on empty square shows error") {
    val app  = AppState.initial
    val next = GameController.handleCommand(app, Command.SelectSquare(Some(pos("e5"))))
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
    val moved = GameController.handleCommand(app, Command.ApplyMove(Move(pos("e2"), pos("e4"))))
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

  test("Help command prints help text") {
    val app = AppState.initial
    val next = GameController.handleCommand(app, Command.Help)
    next.message.exists(_.contains(TerminalView.helpText)) shouldBe true
  }

  test("Unknown command prints error message") {
    val app = AppState.initial
    val next = GameController.handleCommand(app, Command.Unknown("bad cmd"))
    next.message.exists(_.contains("bad cmd")) shouldBe true
  }

  test("Move on a finished game shows error") {
    val app = AppState.initial.copy(status = GameStatus.Stalemate)
    val next = GameController.handleCommand(app, Command.ApplyMove(Move(pos("e2"), pos("e4"))))
    next.message.exists(_.contains("Game is over")) shouldBe true
  }

  test("Pawn promotion required gives error if not provided") {
    // F7 pawn near promotion for white
    val board = Board.empty.put(pos("f7"), Piece(Color.White, PieceType.Pawn))
                             .put(pos("e8"), Piece(Color.Black, PieceType.King))
                             .put(pos("e1"), Piece(Color.White, PieceType.King))
    val app = AppState.initial.copy(game = GameState.initial.withActiveColor(Color.White).copy(board = board))
    
    val next = GameController.handleCommand(app, Command.ApplyMove(Move(pos("f7"), pos("f8"))))
    next.message.exists(_.contains("Pawn promotion required")) shouldBe true
  }

  test("Check message is displayed when move results in check") {
    val board = Board.empty.put(pos("e1"), Piece(Color.White, PieceType.Rook))
                             .put(pos("e8"), Piece(Color.Black, PieceType.King))
                             .put(pos("a1"), Piece(Color.White, PieceType.King))
    val app = AppState.initial.copy(game = GameState.initial.withActiveColor(Color.White).copy(board = board))
    
    // Move rook to e8 to check black king (actually it captures the king here but let's just do a normal check)
    val checkBoard = Board.empty.put(pos("e2"), Piece(Color.White, PieceType.Rook)).put(pos("e8"), Piece(Color.Black, PieceType.King)).put(pos("a1"), Piece(Color.White, PieceType.King))
    val checkApp = AppState.initial.copy(game = GameState.initial.withActiveColor(Color.White).copy(board = checkBoard))
    
    val next = GameController.handleCommand(checkApp, Command.ApplyMove(Move(pos("e2"), pos("e7"))))
    next.message.exists(_.contains("Check!")) shouldBe true
  }

  test("Checkmate message is displayed winning move") {
    // Setup Fool's mate for Black
    val moves = List("f2f3", "e7e5", "g2g4", "d8h4")
    val app = moves.foldLeft(AppState.initial)((a, m) => GameController.handleCommand(a, CommandParser.parse(m, a)))
    app.status match
      case GameStatus.Checkmate(_) => succeed
      case _ => fail(s"Expected Checkmate, got ${app.status}")
    app.message.exists(_.contains("Checkmate!")) shouldBe true
  }

  test("Checkmate message is displayed when White wins") {
    // Scholar's mate
    val moves = List("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7")
    val app = moves.foldLeft(AppState.initial)((a, m) => GameController.handleCommand(a, CommandParser.parse(m, a)))
    app.status match
      case GameStatus.Checkmate(Color.Black) => succeed
      case _ => fail(s"Expected Checkmate for Black, got ${app.status}")
    app.message.exists(_.contains("White wins!")) shouldBe true
  }

  test("Checkmate against White") {
    // Scholar's mate but against white or any fastest mate for Black wins
    // f3, e5, g4, Qh4#
    val start = AppState.initial
    val f3 = GameController.handleCommand(start, Command.ApplyMove(Move(pos("f2"), pos("f3"))))
    val e5 = GameController.handleCommand(f3,    Command.ApplyMove(Move(pos("e7"), pos("e5"))))
    val g4 = GameController.handleCommand(e5,    Command.ApplyMove(Move(pos("g2"), pos("g4"))))
    val qh4 = GameController.handleCommand(g4,   Command.ApplyMove(Move(pos("d8"), pos("h4"))))
    qh4.message.exists(_.contains("Black wins")) shouldBe true
  }

  test("Stalemate message is properly triggered") {
    // Force a stalemate situation manually by setting history and board, then doing a legal move
    // Actually just create a stalemate directly
    val board = Board.empty.put(pos("a1"), Piece(Color.White, PieceType.King))
                           .put(pos("c2"), Piece(Color.Black, PieceType.Queen))
                           .put(pos("h8"), Piece(Color.Black, PieceType.King))
    val game = GameState.initial.withActiveColor(Color.White).copy(board = board)
    val app = AppState.initial.copy(game = game, status = GameRules.computeStatus(game))
    app.status shouldBe GameStatus.Stalemate
  }

  test("Move parser invalid from") {
    CommandParser.parse("z9e4", AppState.initial) match
      case Command.Unknown(_) => succeed
      case _ => fail()
  }

  test("Move parser invalid to") {
    CommandParser.parse("e2z9", AppState.initial) match
      case Command.Unknown(_) => succeed
      case _ => fail()
  }

  test("Stalemate message is displayed on stalemate") {
    // Setup stalemate
    val board2 = Board.empty.put(pos("a8"), Piece(Color.Black, PieceType.King))
                            .put(pos("d7"), Piece(Color.White, PieceType.Queen))
                            .put(pos("e1"), Piece(Color.White, PieceType.King))
    val app2 = AppState.initial.copy(game = GameState.initial.withActiveColor(Color.White).copy(board = board2))
    val next2 = GameController.handleCommand(app2, Command.ApplyMove(Move(pos("d7"), pos("c7"))))
    next2.message.exists(_.contains("Stalemate")) shouldBe true
  }

  test("Draw message is displayed when game is drawn") {
    // Insufficient material (King vs King)
    val board = Board.empty.put(pos("e1"), Piece(Color.White, PieceType.King))
                           .put(pos("e8"), Piece(Color.Black, PieceType.King))
    val app = AppState.initial.copy(game = GameState.initial.withActiveColor(Color.White).copy(board = board))
    val next = GameController.handleCommand(app, Command.ApplyMove(Move(pos("e1"), pos("d1"))))
    next.message.exists(_.contains("Draw by")) shouldBe true
  }

  test("SelectSquare for opponent's piece yields error") {
    val app = AppState.initial
    val next = GameController.handleCommand(app, Command.SelectSquare(Some(pos("e7"))))
    next.message.exists(_.contains("not your piece")) shouldBe true
  }

  test("SelectSquare with no legal moves yields error") {
    val app = AppState.initial
    val next = GameController.handleCommand(app, Command.SelectSquare(Some(pos("e1")))) // King has no moves initially
    next.message.exists(_.contains("No legal moves")) shouldBe true
  }

  test("Undo successful message is displayed") {
    val app1 = GameController.handleCommand(AppState.initial, Command.ApplyMove(Move(pos("e2"), pos("e4"))))
    val app2 = GameController.handleCommand(app1, Command.Undo)
    app2.message.exists(_.contains("Last move undone")) shouldBe true
  }

  test("Resign updates status to Checkmate and shows message") {
    val app = AppState.initial
    val next = GameController.handleCommand(app, Command.Resign)
    next.status shouldBe GameStatus.Checkmate(Color.White)
    next.message.exists(_.contains("resigns. Black wins!")) shouldBe true
  }

  test("AiSuggest highlights best move") {
    val app = AppState.initial
    val next = GameController.handleCommand(app, Command.AiSuggest)
    next.highlights should not be empty
    next.message.exists(_.contains("AI suggests")) shouldBe true
  }

  test("SwitchParser updates active parser") {
    val app = AppState.initial
    val next = GameController.handleCommand(app, Command.SwitchParser("pgn", "regex"))
    next.activePgnParser shouldBe "regex"
    next.message.exists(_.contains("switched to regex")) shouldBe true
    
    val nextInvalid = GameController.handleCommand(app, Command.SwitchParser("pgn", "invalid"))
    nextInvalid.message.exists(_.contains("Unknown PGN parser")) shouldBe true
  }

  test("Odyssey transitions") {
    val app = AppState.initial
    val next = GameController.handleCommand(app, Command.EnterOdyssey)
    next.odyssey shouldBe defined
    next.message.exists(_.contains("Map")) shouldBe true
    
    val back = GameController.handleCommand(next, Command.ExitOdyssey)
    back.odyssey should be (None)
    back.message.exists(_.contains("Exiting")) shouldBe true
  }

  test("StartChallenge loads challenge state") {
    new java.io.File("odyssey_progress.json").delete() // Clear local state for test
    val app = AppState.initial
    // Level 1 is always unlocked
    val next = GameController.handleCommand(app, Command.StartChallenge(1))
    next.odyssey.flatMap(_.currentChallenge) shouldBe defined
    next.message.exists(_.contains("Challenge:")) shouldBe true
    
    // Level 2 is locked
    val locked = GameController.handleCommand(app, Command.StartChallenge(2))
    locked.message.exists(_.contains("Level locked")) shouldBe true
  }
