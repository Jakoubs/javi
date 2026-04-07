package chesscoverage

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import chess.model.*
import chess.controller.*
import chess.view.{TerminalView, CommandParser}
import chess.util.parser.MoveParser
import chess.Main

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}

class CoverageRegressionTest extends AnyFunSuite with Matchers:

  private def stateFromFen(fen: String): GameState =
    GameState.fromFen(fen).fold(msg => fail(msg), identity)

  private def pos(square: String): Pos =
    Pos.fromAlgebraic(square).getOrElse(fail(s"Invalid square: $square"))

  private def messageOf(app: AppState): String =
    app.message.getOrElse(fail("Expected message to be present"))

  test("Board initial and empty serialize to expected FEN placement") {
    Board.initial.toFenPlacement shouldBe "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
    Board.empty.toFenPlacement shouldBe "8/8/8/8/8/8/8/8"
  }

  test("Board fromFenPlacement rejects malformed ranks and invalid pieces") {
    Board.fromFenPlacement("8/8/8/8/8/8/8") shouldBe Left("FEN placement must contain 8 ranks")
    Board.fromFenPlacement("8/8/8/8/8/8/8/88").left.toOption.get should include("exceeds 8 files")
    Board.fromFenPlacement("8/8/8/8/8/8/8/7x").left.toOption.get should include("Invalid piece")
    Board.fromFenPlacement("8/8/8/8/8/8/8/7").left.toOption.get should include("exactly 8 files")
  }

  test("GameState fromFen rejects malformed metadata") {
    GameState.fromFen("8/8/8/8/8/8/8/8 w - - 0").left.toOption.get should include("6 space-separated")
    GameState.fromFen("8/8/8/8/8/8/8/8 x - - 0 1").left.toOption.get should include("active color")
    GameState.fromFen("8/8/8/8/8/8/8/8 w X - 0 1").left.toOption.get should include("castling rights")
    GameState.fromFen("8/8/8/8/8/8/8/8 w - e4 0 1").left.toOption.get should include("en passant target rank")
    GameState.fromFen("8/8/8/8/8/8/8/8 w - - -1 1").left.toOption.get should include("halfmove clock")
    GameState.fromFen("8/8/8/8/8/8/8/8 w - - 0 0").left.toOption.get should include("fullmove number")
  }

  test("GameState withHistory appends the current state") {
    val state = GameState.initial
    val withHistory = state.withHistory
    withHistory.history shouldBe List(state)
  }

  test("CastlingRights fromFen rejects duplicate rights") {
    CastlingRights.fromFen("KK") shouldBe None
  }

  test("Piece fromFenChar rejects non-letter characters") {
    Piece.fromFenChar('1') shouldBe None
  }

  test("GameRules detects fifty-move rule") {
    val state = stateFromFen("4k3/8/8/8/8/8/8/4K3 w - - 100 1")
    GameRules.computeStatus(state) shouldBe GameStatus.Draw("50-move rule")
  }

  test("GameRules detects threefold repetition from history") {
    val repeated = stateFromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    val state = repeated.copy(history = List(repeated, repeated))
    GameRules.computeStatus(state) shouldBe GameStatus.Draw("threefold repetition")
  }

  test("GameRules detects insufficient material") {
    val state = stateFromFen("4k3/8/8/8/8/8/8/3BK3 w - - 0 1")
    GameRules.computeStatus(state) shouldBe GameStatus.Draw("insufficient material")
  }

  test("GameRules detects king versus king as insufficient material") {
    val state = stateFromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    GameRules.computeStatus(state) shouldBe GameStatus.Draw("insufficient material")
  }

  test("applyMove updates castling rights for king move, rook move and rook capture") {
    val kingMoveState = stateFromFen("4k2r/8/8/8/8/8/8/R3K2R w KQk - 0 1")
    val afterKingMove = GameRules.applyMove(kingMoveState, Move(pos("e1"), pos("f1")))
    afterKingMove.castlingRights.whiteKingSide shouldBe false
    afterKingMove.castlingRights.whiteQueenSide shouldBe false

    val rookMoveState = stateFromFen("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1")
    val afterRookMove = GameRules.applyMove(rookMoveState, Move(pos("a1"), pos("a2")))
    afterRookMove.castlingRights.whiteQueenSide shouldBe false

    val rookCaptureState = stateFromFen("4k2r/8/8/8/8/8/7Q/4K3 w k - 0 1")
    val afterCapture = GameRules.applyMove(rookCaptureState, Move(pos("h2"), pos("h8")))
    afterCapture.castlingRights.blackKingSide shouldBe false
  }

  test("applyMove updates promotion, en-passant target and clocks") {
    val blackPawnAdvance = stateFromFen("4k3/3p4/8/8/8/8/8/4K3 b - - 7 4")
    val afterAdvance = GameRules.applyMove(blackPawnAdvance, Move(pos("d7"), pos("d5")))
    afterAdvance.enPassantTarget shouldBe Some(pos("d6"))
    afterAdvance.halfMoveClock shouldBe 0
    afterAdvance.fullMoveNumber shouldBe 5

    val knightMove = stateFromFen("4k3/8/8/8/8/8/6N1/4K3 w - - 7 4")
    val afterKnightMove = GameRules.applyMove(knightMove, Move(pos("g2"), pos("h4")))
    afterKnightMove.halfMoveClock shouldBe 8
    afterKnightMove.fullMoveNumber shouldBe 4

    val promotion = stateFromFen("7k/6P1/8/8/8/8/8/4K3 w - - 0 1")
    val afterPromotion = GameRules.applyMove(promotion, Move(pos("g7"), pos("g8"), Some(PieceType.Queen)))
    afterPromotion.board.get(pos("g8")) shouldBe Some(Piece(Color.White, PieceType.Queen))
  }

  test("GameRules reports check without immediate draw override") {
    val state = stateFromFen("4q2k/8/8/8/8/8/8/4K3 w - - 0 1")
    GameRules.computeStatus(state) shouldBe GameStatus.Check(Color.White)
  }

  test("controller handles promotion-required, finished-game move, resign, help and unknown command") {
    val promotionApp = AppState(
      game = stateFromFen("7k/4P3/8/8/8/8/8/4K3 w - - 0 1"),
      status = GameStatus.Playing
    )
    messageOf(GameController.handleCommand(promotionApp, Command.ApplyMove(Move(pos("e7"), pos("e8"))))) should include("promotion required")

    val finishedApp = AppState.initial.copy(status = GameStatus.Stalemate)
    messageOf(GameController.handleCommand(finishedApp, Command.ApplyMove(Move(pos("e2"), pos("e4"))))) should include("Game is over")

    val resigned = GameController.handleCommand(AppState.initial, Command.Resign)
    resigned.status shouldBe GameStatus.Checkmate(Color.White)
    messageOf(resigned) should include("resigns")

    messageOf(GameController.handleCommand(AppState.initial, Command.Help)) should include("Chess Commands")
    messageOf(GameController.handleCommand(AppState.initial, Command.Unknown("bad"))) should include("bad")
  }

  test("show moves rejects opponent piece and pieces without legal targets") {
    val opponentPieceApp = AppState.initial
    messageOf(GameController.handleCommand(opponentPieceApp, Command.SelectSquare(Some(pos("e7"))))) should include("not your piece")

    val blockedApp = AppState(
      game = stateFromFen("4k3/8/8/8/8/4p3/4P3/4K3 w - - 0 1"),
      status = GameStatus.Playing
    )
    messageOf(GameController.handleCommand(blockedApp, Command.SelectSquare(Some(pos("e2"))))) should include("No legal moves")
  }

  test("command parser accepts aliases and rejects invalid promotion suffix") {
    CommandParser.parse("draw", AppState.initial) shouldBe Command.OfferDraw
    CommandParser.parse("help", AppState.initial) shouldBe Command.Help
    CommandParser.parse("newgame", AppState.initial) shouldBe Command.NewGame
    CommandParser.parse("exit", AppState.initial) shouldBe Command.Quit
    CommandParser.parse("e7e8x", AppState.initial) shouldBe a[Command.Unknown]
    CommandParser.parse("e2", AppState.initial) shouldBe a[Command.Unknown]
  }

  test("MoveGenerator covers queen, black pawns and extra castling branches") {
    val queenState = stateFromFen("7k/8/8/8/4Q3/8/8/K7 w - - 0 1")
    MoveGenerator.legalMovesFrom(queenState, pos("e4")) should have length 27

    val blackPawnState = stateFromFen("4k3/8/8/8/8/8/3p4/4K3 b - - 0 1")
    val blackTargets = MoveGenerator.legalMovesFrom(blackPawnState, pos("d2")).map(_.to)
    blackTargets should contain(pos("d1"))

    val queenSideCastle = stateFromFen("r3k3/8/8/8/8/8/8/R3K3 w Qq - 0 1")
    MoveGenerator.legalMovesFrom(queenSideCastle, pos("e1")).map(_.to) should contain(pos("c1"))
    MoveGenerator.legalMovesFrom(queenSideCastle.withActiveColor(Color.Black).copy(), pos("e8")).map(_.to) should contain(pos("c8"))

    val noRookCastle = stateFromFen("4k3/8/8/8/8/8/8/4K3 w K - 0 1")
    MoveGenerator.legalMovesFrom(noRookCastle, pos("e1")).map(_.to) should not contain pos("g1")

    val checkedCastle = stateFromFen("4r2k/8/8/8/8/8/8/4K2R w K - 0 1")
    MoveGenerator.legalMovesFrom(checkedCastle, pos("e1")).map(_.to) should not contain pos("g1")
  }

  test("MoveGenerator attack detection covers missing king and blocked attacks") {
    val noBlackKing = stateFromFen("8/8/8/8/8/8/8/4K3 b - - 0 1")
    MoveGenerator.isInCheck(noBlackKing, Color.Black) shouldBe false

    val blockedAttack = stateFromFen("4k3/8/8/8/4b3/4P3/8/4K3 w - - 0 1")
    MoveGenerator.isInCheck(blockedAttack, Color.White) shouldBe false
  }

  test("TerminalView renders all footer states, highlights and flipped coordinates") {
    val state = stateFromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    TerminalView.render(state, GameStatus.Check(Color.White)) should include("is in check")
    TerminalView.render(state, GameStatus.Checkmate(Color.Black)) should include("Checkmate!")
    TerminalView.render(state, GameStatus.Stalemate) should include("Stalemate!")
    TerminalView.render(state, GameStatus.Draw("agreement")) should include("Draw")

    val highlighted = TerminalView.render(
      state = state,
      status = GameStatus.Playing,
      highlights = Set(pos("e2")),
      lastMove = Some(Move(pos("e1"), pos("e2"))),
      flipped = true
    )
    highlighted should include("h")
    highlighted should include("a")
  }

  test("TerminalView helpers and StdConsoleIO are directly executable") {
    TerminalView.success("ok") should include("ok")
    TerminalView.error("bad") should include("bad")
    TerminalView.info("info") should include("info")

    val input = ByteArrayInputStream("quit\n".getBytes("UTF-8"))
    val output = ByteArrayOutputStream()
    val printStream = PrintStream(output, true, "UTF-8")

    scala.Console.withIn(input) {
      scala.Console.withOut(printStream) {
      StdConsoleIO.print("x")
      StdConsoleIO.clear()
      StdConsoleIO.readLine() shouldBe Some("quit")
      }
    }
  }

  test("Main.main prints startup text and exits on quit input") {
    val input = ByteArrayInputStream("quit\n".getBytes("UTF-8"))
    val output = ByteArrayOutputStream()
    val printStream = PrintStream(output, true, "UTF-8")

    scala.Console.withIn(input) {
      scala.Console.withOut(printStream) {
      Main.main(Array.empty)
      }
    }

    output.toString("UTF-8") should include("Starting Chess")
  }
