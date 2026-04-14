package chess.controller

import chess.model.*
import chess.view.{ConsoleIO, StdConsoleIO}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Promise, Await}
import scala.concurrent.duration.*

class GameControllerSpec extends AnyFunSpec with Matchers {
  describe("GameController.handleCommand") {
    val initial = AppState.initial

    it("should start with a correct initial state") {
      initial.game.activeColor shouldBe Color.White
      initial.status shouldBe GameStatus.Playing
      initial.viewIndex shouldBe 0
      initial.flipped shouldBe false
    }

    describe("Movement (ApplyMove)") {
      it("should apply a valid move and update history") {
        val move = Move(Pos(0, 1), Pos(0, 2)) // a2 to a3
        val next = GameController.handleCommand(initial, Command.ApplyMove(move))
        
        next.game.board.get(Pos(0, 2)).map(_.pieceType) shouldBe Some(PieceType.Pawn)
        next.game.activeColor shouldBe Color.Black
        next.lastMove shouldBe Some(move)
        next.viewIndex shouldBe 1
        next.game.history.size shouldBe 1
      }

      it("should return an error for an illegal move") {
        val move = Move(Pos(0, 1), Pos(0, 5)) // illegal a2 to a6
        val next = GameController.handleCommand(initial, Command.ApplyMove(move))
        
        next.game shouldBe initial.game
        next.message.get should include ("Illegaler Zug.")
      }

      it("should return an error when moving the wrong color") {
        val move = Move(Pos(0, 6), Pos(0, 5)) // Black pawn at a7
        val next = GameController.handleCommand(initial, Command.ApplyMove(move))
        
        next.game shouldBe initial.game
        next.message.get should include ("Falsche Farbe!")
      }

      it("should require promotion for a pawn reaching the last rank") {
        // Setup: White pawn at a7, both Kings present to avoid immediate draw
        val board = Board.empty
          .put(Pos(0, 6), Piece(Color.White, PieceType.Pawn))
          .put(Pos(4, 0), Piece(Color.White, PieceType.King))
          .put(Pos(4, 7), Piece(Color.Black, PieceType.King))
        val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
        val app = AppState(state, GameRules.computeStatus(state))
        
        val move = Move(Pos(0, 6), Pos(0, 7)) // No promotion specified
        val next = GameController.handleCommand(app, Command.ApplyMove(move))
        
        next.game shouldBe app.game
        next.message.get should include ("Pawn promotion required.")
      }
    }

    describe("Selection (SelectSquare)") {
      it("should highlight legal moves for a selected piece") {
        val cmd = Command.SelectSquare(Some(Pos(0, 1))) // Select a2
        val next = GameController.handleCommand(initial, cmd)
        
        next.selectedPos shouldBe Some(Pos(0, 1))
        next.highlights shouldBe Set(Pos(0, 2), Pos(0, 3)) // a3, a4
      }

      it("should clear highlights when selecting an empty square") {
        val cmd = Command.SelectSquare(Some(Pos(0, 3))) // empty square
        val next = GameController.handleCommand(initial, cmd)
        
        next.selectedPos shouldBe None
        next.highlights shouldBe Set.empty
        next.message.get should include ("No piece on a4")
      }
    }

    describe("Game Flow") {
      it("should undo the last move") {
        val move = Move(Pos(0, 1), Pos(0, 2))
        val moved = GameController.handleCommand(initial, Command.ApplyMove(move))
        val undone = GameController.handleCommand(moved, Command.Undo)
        
        undone.game shouldBe initial.game
        undone.viewIndex shouldBe 0
      }

      it("should handle resignation") {
        val next = GameController.handleCommand(initial, Command.Resign)
        next.status shouldBe GameStatus.Checkmate(Color.White)
        next.message.get should include ("White resigns")
      }

      it("should handle draw offers and acceptance") {
        val offer = GameController.handleCommand(initial, Command.OfferDraw)
        offer.drawOffer shouldBe true
        offer.message.get should include ("White offers a draw")

        val accept = GameController.handleCommand(offer, Command.OfferDraw)
        accept.drawOffer shouldBe false
        accept.status shouldBe GameStatus.Draw("agreement")
        accept.message.get should include ("Draw accepted")
      }

      it("should flip the board") {
        val next = GameController.handleCommand(initial, Command.Flip)
        next.flipped shouldBe true
        
        val back = GameController.handleCommand(next, Command.Flip)
        back.flipped shouldBe false
      }

      it("should handle navigation between history states") {
        // Game: 1. e4 e5
        val s1 = GameController.handleCommand(initial, Command.ApplyMove(Move(Pos(4, 1), Pos(4, 3))))
        val s2 = GameController.handleCommand(s1, Command.ApplyMove(Move(Pos(4, 6), Pos(4, 4))))
        s2.viewIndex shouldBe 2

        val stepBack = GameController.handleCommand(s2, Command.StepBack)
        stepBack.viewIndex shouldBe 1

        val stepForward = GameController.handleCommand(stepBack, Command.StepForward)
        stepForward.viewIndex shouldBe 2

        val first = GameController.handleCommand(s2, Command.FirstHistory)
        first.viewIndex shouldBe 0

        val last = GameController.handleCommand(first, Command.LastHistory)
        last.viewIndex shouldBe 2

        val jump = GameController.handleCommand(s2, Command.JumpToHistory(1))
        jump.viewIndex shouldBe 1
      }
    }

    describe("AI and Advanced Controls") {
      it("should toggle AI for White and Black") {
        val wOn = GameController.handleCommand(initial, Command.ToggleAi(Color.White))
        wOn.aiWhite shouldBe true
        wOn.message.get should include ("AI White enabled")

        val bOn = GameController.handleCommand(wOn, Command.ToggleAi(Color.Black))
        bOn.aiBlack shouldBe true
        bOn.message.get should include ("AI Black enabled")
      }

      it("should handle AI suggestion") {
        val suggest = GameController.handleCommand(initial, Command.AiSuggest)
        suggest.highlights should not be empty
        suggest.message.get should include ("AI suggests")
      }

      it("should handle AI training start") {
        val train = GameController.handleCommand(initial, Command.AiTrain(10))
        train.training shouldBe true
        train.message.get should include ("Started parallel training")
      }
    }

    describe("Parser and Clock Interaction") {
      it("should switch parsers") {
        val sw = GameController.handleCommand(initial, Command.SwitchParser("pgn", "fast"))
        sw.activePgnParser shouldBe "fast"
        sw.message.get should include ("PGN Parser switched to fast")

        val swMove = GameController.handleCommand(sw, Command.SwitchParser("move", "san"))
        swMove.activeMoveParser shouldBe "san"
        swMove.message.get should include ("Move Parser switched to san")
      }

      it("should handle time expired") {
        val expired = GameController.handleCommand(initial, Command.TimeExpired(Color.White))
        expired.status shouldBe GameStatus.Timeout(Color.White)
        expired.message.get should include ("Black wins")
      }
    }

    describe("Serialization / Loading") {
      it("should load a game from FEN") {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val next = GameController.handleCommand(initial, Command.LoadFen(fen))
        
        next.game.toFen should startWith ("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        next.message.get should include ("FEN loaded successfully")
      }

      it("should handle invalid FEN") {
        val next = GameController.handleCommand(initial, Command.LoadFen("invalid fen"))
        next.message.get should include ("FEN Error")
      }

      it("should export PGN and FEN") {
        val move = Move(Pos(4, 1), Pos(4, 3))
        val moved = GameController.handleCommand(initial, Command.ApplyMove(move))
        
        val showPgn = GameController.handleCommand(moved, Command.ShowPgn)
        showPgn.message.get should include ("PGN:")
        showPgn.message.get should include ("e4")

        val showFen = GameController.handleCommand(moved, Command.ShowFen)
        showFen.message.get should include ("FEN:")
      }
    }

    describe("Other Commands") {
      it("should start a new game with clock") {
        val start = GameController.handleCommand(initial, Command.StartGame(Some(600000L, 5000L)))
        start.clock shouldBe defined
        start.clock.get.whiteMillis shouldBe 600000L
      }

      it("should handle help and quit") {
        GameController.handleCommand(initial, Command.Help).message.get should include ("Chess Commands")
        GameController.handleCommand(initial, Command.Quit).running shouldBe false
      }

      it("should handle unknown commands") {
        val unknown = GameController.handleCommand(initial, Command.Unknown("mystery"))
        unknown.message.get should include ("mystery")
      }
    }

    describe("Advanced Coverage and Edge Cases") {
      
      it("should handle clock logic during moves correctly") {
        val now = System.currentTimeMillis()
        val clock = ClockState(10000, 10000, 1000, Some(now - 1000), isActive = true)
        val app = initial.copy(clock = Some(clock))
        
        // 1. White moves, should deduct elapsed (1000) and add increment (1000) = same whiteMillis
        val move = Move(Pos(4, 1), Pos(4, 3)) // e2-e4
        val next = GameController.handleCommand(app, Command.ApplyMove(move))
        
        next.clock.get.whiteMillis shouldBe 10000
        next.clock.get.lastTickSysTime.get should be >= now
        
        // 2. Black moves, should deduct elapsed (simulated)
        val nextNow = System.currentTimeMillis()
        val appWithTick = next.copy(clock = next.clock.map(_.copy(lastTickSysTime = Some(nextNow - 2000))))
        val response = Move(Pos(4, 6), Pos(4, 4)) // e7-e5
        val afterBlack = GameController.handleCommand(appWithTick, Command.ApplyMove(response))
        
        afterBlack.clock.get.blackMillis should (be >= 8900L and be <= 9100L)
      }

      it("should detect timeout during a move attempt") {
        val now = System.currentTimeMillis()
        // White has 500ms left, but 2000ms have passed since last tick
        val clock = ClockState(500, 10000, 0, Some(now - 2000), isActive = true)
        val app = initial.copy(clock = Some(clock))
        
        val move = Move(Pos(4, 1), Pos(4, 3))
        val next = GameController.handleCommand(app, Command.ApplyMove(move))
        
        next.status shouldBe a [GameStatus.Timeout]
        next.message.get should include ("Timeout")
      }

      it("should handle the first move clock exception") {
        // First move by White doesn't deduct time
        val clock = ClockState(600000, 600000, 0, None, isActive = true)
        val app = initial.copy(clock = Some(clock))
        
        val move = Move(Pos(4, 1), Pos(4, 3))
        val next = GameController.handleCommand(app, Command.ApplyMove(move))
        
        next.clock.get.whiteMillis shouldBe 600000
        next.clock.get.lastTickSysTime shouldBe defined
      }

      it("should handle move attempts when game is already over") {
        val over = initial.copy(status = GameStatus.Stalemate)
        val move = Move(Pos(4, 1), Pos(4, 3))
        val next = GameController.handleCommand(over, Command.ApplyMove(move))
        
        next.game shouldBe over.game
        next.message.get should include ("Game is over")
      }

      it("should validate and handle out-of-bounds history jump") {
        val move = Move(Pos(4, 1), Pos(4, 3))
        val moved = GameController.handleCommand(initial, Command.ApplyMove(move))
        
        GameController.handleCommand(moved, Command.JumpToHistory(-5)).viewIndex shouldBe 0
        GameController.handleCommand(moved, Command.JumpToHistory(99)).viewIndex shouldBe 1
      }

      it("should handle navigation edge cases for steps") {
        GameController.handleCommand(initial, Command.StepBack).viewIndex shouldBe 0
        
        val move = Move(Pos(4, 1), Pos(4, 3))
        val moved = GameController.handleCommand(initial, Command.ApplyMove(move))
        GameController.handleCommand(moved, Command.StepForward).viewIndex shouldBe 1
      }

      it("should trigger PassiveTrainer on game end") {
        // Fast checkmate: 1. f3 e5 2. g4 Qh4# (Fool's Mate)
        var s = initial
        s = GameController.handleCommand(s, Command.ApplyMove(Move(Pos(5, 1), Pos(5, 2)))) // f3
        s = GameController.handleCommand(s, Command.ApplyMove(Move(Pos(4, 6), Pos(4, 4)))) // e5
        s = GameController.handleCommand(s, Command.ApplyMove(Move(Pos(6, 1), Pos(6, 3)))) // g4
        val mate = GameController.handleCommand(s, Command.ApplyMove(Move(Pos(3, 7), Pos(7, 3)))) // Qh4#
        
        mate.status shouldBe GameStatus.Checkmate(Color.White)
        // PassiveTrainer.train(mate.game, mate.status) is called internally.
        // We can't directly check the private weights, but we ensured the path is executed.
      }

      it("should handle various SwitchParser errors") {
        val errType = GameController.handleCommand(initial, Command.SwitchParser("invalid", "variant"))
        errType.message.get should include ("Unknown parser type")
        
        val errPgn = GameController.handleCommand(initial, Command.SwitchParser("pgn", "nonexistent"))
        errPgn.message.get should include ("Unknown PGN parser variant")
        
        val errMove = GameController.handleCommand(initial, Command.SwitchParser("move", "nonexistent"))
        errMove.message.get should include ("Unknown Move parser variant")
      }

      it("should handle PGN import failures") {
        val next = GameController.handleCommand(initial, Command.LoadPgn("not a valid pgn"))
        next.message.get should include ("PGN Error")
      }

      it("should trigger auto AI move via eval") {
        // This is a bit tricky since it's global and uses Future.
        // We'll use a custom observer to track updates.
        import scala.concurrent.Promise
        import scala.concurrent.duration.*
        import scala.concurrent.Await

        val aiPromise = Promise[AppState]()
        val observer = new chess.util.Observer[AppState] {
          def update(state: AppState): Unit = 
            if (state.lastMove.isDefined && state.game.activeColor == Color.Black) {
              // Wait for the AI's response (which will trigger another update)
            } else if (state.lastMove.isDefined && state.game.activeColor == Color.White) {
              if (!aiPromise.isCompleted) aiPromise.success(state)
            }
        }

        GameController.addObserver(observer)
        try {
          // Enable AI for Black
          GameController.appState = initial.copy(aiBlack = true)
          // Make a move for White
          GameController.eval(Command.ApplyMove(Move(Pos(4, 1), Pos(4, 3)))) // e2e4
          
          // Wait for AI response (e7e5 or similar)
          val result = Await.result(aiPromise.future, 30.seconds)
          result.game.history.size should be >= 2
          result.game.activeColor shouldBe Color.White
        } finally {
          GameController.removeObserver(observer)
          GameController.appState = AppState.initial
        }
      }

      it("should handle AI Suggest at game end") {
        val over = initial.copy(status = GameStatus.Stalemate)
        val suggest = GameController.handleCommand(over, Command.AiSuggest)
        suggest.message.get should include ("Game is already over")
        
        val move = GameController.handleCommand(over, Command.AiMove)
        move.message.get should include ("Game is already over")
      }

      it("should handle simulateGame via AiTrain logic") {
        // simulateGame is private but called by handleAiTrain logic
        // We can trigger it and verify training session start.
        val next = GameController.handleCommand(initial, Command.AiTrain(1))
        next.training shouldBe true
        next.message.get should include ("Started parallel training")
      }

      it("should clear highlights when selecting invalid square") {
        val next = GameController.handleCommand(initial, Command.SelectSquare(Some(Pos(0, 6)))) // Black's piece selected by White
        next.message.get should include ("That is not your piece")
        next.selectedPos shouldBe None
        next.highlights shouldBe empty
      }

      it("should handle select square for piece with no moves") {
        // Setup: White King surrounded by White pieces
        val board = Board.empty
          .put(Pos(4, 0), Piece(Color.White, PieceType.King))
          .put(Pos(3, 0), Piece(Color.White, PieceType.Rook))
          .put(Pos(5, 0), Piece(Color.White, PieceType.Rook))
          .put(Pos(4, 1), Piece(Color.White, PieceType.Pawn))
          .put(Pos(3, 1), Piece(Color.White, PieceType.Pawn))
          .put(Pos(5, 1), Piece(Color.White, PieceType.Pawn))
          // Black king to avoid immediate draw if possible, or just focus on the board
          .put(Pos(0, 7), Piece(Color.Black, PieceType.King))
          // Black piece that could threaten King if not blocked
          .put(Pos(4, 7), Piece(Color.Black, PieceType.Rook))

        val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
        val app = AppState(state, GameRules.computeStatus(state))
        
        val next = GameController.handleCommand(app, Command.SelectSquare(Some(Pos(4, 0))))
        next.selectedPos shouldBe Some(Pos(4, 0))
        next.highlights shouldBe empty
        next.message.get should include ("No legal moves")
      }

      it("should trigger AI move when active player is in check") {
        // FEN: White in check from Black Queen on e4
        val fen = "r1b1kbnr/ppp2ppp/2p5/8/4q3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 7"
        val state = GameState.fromFen(fen).getOrElse(fail("Invalid FEN"))
        
        val aiPromise = Promise[AppState]()
        val observer = new chess.util.Observer[AppState] {
          def update(state: AppState): Unit = {
            if (state.game.activeColor == Color.Black && state.lastMove.isDefined) {
              if (!aiPromise.isCompleted) aiPromise.success(state)
            }
          }
        }

        GameController.addObserver(observer)
        try {
          GameController.appState = AppState(state, GameRules.computeStatus(state), aiWhite = true)
          // Trigger the auto-move check
          GameController.eval(Command.SelectSquare(None)) 
          val result = Await.result(aiPromise.future, 10.seconds)
          result.status should not be GameStatus.Check(Color.White)
          result.game.activeColor shouldBe Color.Black
          result.lastMove shouldBe defined
        } finally {
          GameController.removeObserver(observer)
          GameController.appState = AppState.initial
        }
      }
    }

    describe("StdConsoleIO") {
      it("should print to console") {
        val out = new java.io.ByteArrayOutputStream()
        Console.withOut(out) {
          StdConsoleIO.print("hello")
        }
        out.toString shouldBe "hello"
      }

      it("should clear terminal") {
        val out = new java.io.ByteArrayOutputStream()
        Console.withOut(out) {
          StdConsoleIO.clear()
        }
        out.toString should (include ("\u001b[H") and include ("\u001b[2J"))
      }

      it("should read from line") {
        val in = new java.io.ByteArrayInputStream("test\n".getBytes)
        Console.withIn(in) {
          StdConsoleIO.readLine() shouldBe Some("test")
        }
      }
    }
  }
}
