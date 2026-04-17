package chess.ai

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class PassiveTrainerSpec extends AnyFunSpec with Matchers with BeforeAndAfterEach {

  override def beforeEach(): Unit = {
    Evaluator.resetWeights()
  }

  describe("PassiveTrainer") {

    it("should adjust weights when White wins by checkmate") {
      val initialPawnWeight = Evaluator.evaluate(GameState.fromFen("8/8/8/8/8/8/P7/8 w - - 0 1").getOrElse(fail("FEN error")))
      
      // White wins (Black is checkmated) - pawns included so pawn weights get updated
      val mateFen = "k7/1Q6/2K5/8/8/8/P7/8 b - - 0 1"
      val state = GameState.fromFen(mateFen).getOrElse(fail("FEN error"))
      val status = GameRules.computeStatus(state)
      status shouldBe a[GameStatus.Checkmate]
      
      PassiveTrainer.train(state, status)
      
      // Since White won, White's weight at (0,1) for Pawn should have increased in trajectory calculation
      // But let's check general material weights.
      // After training, Evaluator should have different weights.
      val newPawnWeight = Evaluator.evaluate(GameState.fromFen("8/8/8/8/8/8/P7/8 w - - 0 1").getOrElse(fail("FEN error")))
      newPawnWeight should not be initialPawnWeight
    }

    it("should adjust weights when Black wins by checkmate") {
      val initialPawnWeight = Evaluator.evaluate(GameState.fromFen("8/8/8/8/8/8/p7/8 b - - 0 1").getOrElse(fail("FEN error")))
      
      // Black wins (White is checkmated)
      val mateFen = "K7/1q6/2k5/8/8/8/p7/8 w - - 1 1"
      val state = GameState.fromFen(mateFen).getOrElse(fail("FEN error"))
      val status = GameRules.computeStatus(state)
      status shouldBe a[GameStatus.Checkmate]
      
      PassiveTrainer.train(state, status)
      
      val newPawnWeight = Evaluator.evaluate(GameState.fromFen("8/8/8/8/8/8/p7/8 b - - 0 1").getOrElse(fail("FEN error")))
      newPawnWeight should not be initialPawnWeight
    }

    it("should not adjust weights on Draw or Stalemate") {
      val drawFen = "k7/1R6/2K5/8/8/8/8/8 b - - 0 1"
      val state = GameState.fromFen(drawFen).getOrElse(fail("FEN error"))
      val status = GameRules.computeStatus(state)
      status shouldBe GameStatus.Stalemate
      
      val beforeEvaluator = Evaluator.evaluate(GameState.initial)
      PassiveTrainer.train(state, status)
      val afterEvaluator = Evaluator.evaluate(GameState.initial)
      
      afterEvaluator shouldBe beforeEvaluator
    }

    it("should update trajectory for states in history") {
      // Create a state with history
      val state1 = GameState.fromFen("k7/8/2K5/8/8/8/8/8 w - - 0 1").getOrElse(fail("FEN error"))
      val state2 = GameState.fromFen("k7/1Q6/2K5/8/8/8/8/8 b - - 0 1").getOrElse(fail("FEN error")) // Mate
      val stateWithHistory = state2.copy(history = List(state1))
      
      val status = GameRules.computeStatus(state2)
      
      PassiveTrainer.train(stateWithHistory, status)
      // If it works, it shouldn't crash and should have updated weights for both states
    }

    describe("Opening Heuristics") {
      
      it("should update center pawn weights") {
        val initialProb = Evaluator.getOpeningCenterPawn.get()
        // White won, White has center pawns on e4/d4, Black has only king
        val state = GameState.fromFen("4k3/8/8/8/3PP3/8/8/8 w - - 0 1") match {
          case Left(err) => fail(s"FEN error: $err")
          case Right(s) => s
        }
        val status = GameStatus.Checkmate(Color.Black) // White won
        
        PassiveTrainer.train(state, status)
        
        Evaluator.getOpeningCenterPawn.get() should be > initialProb
      }

      it("should update center knight weights") {
        val initialProb = Evaluator.getOpeningCenterKnight.get()
        // White won, White has knights on c3/f3, Black has only king
        val state = GameState.fromFen("4k3/8/8/8/8/2N2N2/8/8 w - - 0 1") match {
          case Left(err) => fail(s"FEN error: $err")
          case Right(s) => s
        }
        val status = GameStatus.Checkmate(Color.Black) // White won
        
        PassiveTrainer.train(state, status)
        
        Evaluator.getOpeningCenterKnight.get() should be > initialProb
      }

      it("should penalize undeveloped minors") {
        val initialProb = Evaluator.getOpeningUndevelopedMinor.get()
        // White won but has undeveloped minors, Black has no pieces at home
        val state = GameState.fromFen("4k3/8/8/8/8/8/8/RNBQKBNR w - - 0 1") match {
          case Left(err) => fail(s"FEN error: $err")
          case Right(s) => s
        }
        val status = GameStatus.Checkmate(Color.Black) // White won
        
        PassiveTrainer.train(state, status)
        
        Evaluator.getOpeningUndevelopedMinor.get() should be < initialProb
      }

      it("should penalize early queen") {
        val initialProb = Evaluator.getOpeningEarlyQueen.get()
        // White queen moved but minors stayed home, Black has no pieces at home
        val state = GameState.fromFen("4k3/8/8/8/3Q4/8/8/RNB1KBNR w - - 0 1") match {
          case Left(err) => fail(s"FEN error: $err")
          case Right(s) => s
        }
        val status = GameStatus.Checkmate(Color.Black) // White won
        
        PassiveTrainer.train(state, status)
        
        Evaluator.getOpeningEarlyQueen.get() should be < initialProb
      }

      it("should update castled bonus") {
        val initialProb = Evaluator.getOpeningCastledBonus.get()
        // White castled (king at g1), Black has no pieces at home
        val state = GameState.fromFen("4k3/8/8/8/8/8/8/RNBQ1RK1 w - - 0 1") match {
          case Left(err) => fail(s"FEN error: $err")
          case Right(s) => s
        }
        val status = GameStatus.Checkmate(Color.Black) // White won
        
        PassiveTrainer.train(state, status)
        
        Evaluator.getOpeningCastledBonus.get() should be > initialProb
      }

      it("should penalize lost castling rights") {
        val initialProb = Evaluator.getOpeningLostCastle.get()
        // White king moved to d3, Black has king at e8 and castling rights (kq)
        val state = GameState.fromFen("4k3/8/8/8/8/3K4/8/RNBQ1BNR w kq - 0 1") match {
          case Left(err) => fail(s"FEN error: $err")
          case Right(s) => s
        }
        val status = GameStatus.Checkmate(Color.Black) // White won
        
        PassiveTrainer.train(state, status)
        
        Evaluator.getOpeningLostCastle.get() should be < initialProb
      }

      it("should penalize uncastled king at home without rights") {
        val initialProb = Evaluator.getOpeningUncastled.get()
        // White king at home but rights gone, Black has rights (kq)
        val state = GameState.fromFen("4k3/8/8/8/8/8/8/RNBQKBNR w kq - 0 1") match {
          case Left(err) => fail(s"FEN error: $err")
          case Right(s) => s
        }
        val status = GameStatus.Checkmate(Color.Black) // White won
        
        PassiveTrainer.train(state, status)
        
        Evaluator.getOpeningUncastled.get() should be < initialProb
      }

      it("should penalize missing f and h pawns (Weak F/H)") {
        val initialProbF = Evaluator.getOpeningWeakPawnF.get()
        // White pawns missing from f2 and h2. Black MUST have pawns on f7, h7 so Black's update doesn't trigger and cancel it.
        val state = GameState.fromFen("4k3/5p1p/8/8/8/8/P1P1P1P1/RNBQKBNR w - - 0 1") match {
          case Left(err) => fail(s"FEN error: $err")
          case Right(s) => s
        }
        val status = GameStatus.Checkmate(Color.Black) // White won
        
        PassiveTrainer.train(state, status)
        
        Evaluator.getOpeningWeakPawnF.get() should be < initialProbF
      }

      describe("Black Opening Heuristics") {
        it("should update center pawn weights for Black") {
          val initialProb = Evaluator.getOpeningCenterPawn.get()
          // Black won, Black has center pawns on e5/d5, White has only king
          val state = GameState.fromFen("8/8/8/3pp3/8/8/8/4K3 b - - 0 1").getOrElse(fail("FEN error"))
          val status = GameStatus.Checkmate(Color.White) // Black won
          
          PassiveTrainer.train(state, status)
          Evaluator.getOpeningCenterPawn.get() should be > initialProb
        }

        it("should update center knight weights for Black") {
          val initialProb = Evaluator.getOpeningCenterKnight.get()
          // Black won, Black has knights on c6/f6
          val state = GameState.fromFen("8/8/2n2n2/8/8/8/8/4K3 b - - 0 1").getOrElse(fail("FEN error"))
          val status = GameStatus.Checkmate(Color.White) // Black won
          
          PassiveTrainer.train(state, status)
          Evaluator.getOpeningCenterKnight.get() should be > initialProb
        }

        it("should penalize undeveloped minors for Black") {
          val initialProb = Evaluator.getOpeningUndevelopedMinor.get()
          // Black won but has undeveloped minors at home (rank 8)
          val state = GameState.fromFen("rnbqkbnr/8/8/8/8/8/8/4K3 b - - 0 1").getOrElse(fail("FEN error"))
          val status = GameStatus.Checkmate(Color.White) // Black won
          
          PassiveTrainer.train(state, status)
          Evaluator.getOpeningUndevelopedMinor.get() should be < initialProb
        }

        it("should penalize early queen for Black") {
          val initialProb = Evaluator.getOpeningEarlyQueen.get()
          // Black queen moved to d5, minors stayed home
          val state = GameState.fromFen("rnb1kbnr/8/8/3q4/8/8/8/4K3 b - - 0 1").getOrElse(fail("FEN error"))
          val status = GameStatus.Checkmate(Color.White) // Black won
          
          PassiveTrainer.train(state, status)
          Evaluator.getOpeningEarlyQueen.get() should be < initialProb
        }

        it("should update castled bonus for Black") {
          val initialProb = Evaluator.getOpeningCastledBonus.get()
          // Black castled (king at g8)
          val state = GameState.fromFen("rnbq1rk1/8/8/8/8/8/8/4K3 b - - 0 1").getOrElse(fail("FEN error"))
          val status = GameStatus.Checkmate(Color.White) // Black won
          
          PassiveTrainer.train(state, status)
          Evaluator.getOpeningCastledBonus.get() should be > initialProb
        }

        it("should penalize lost castling rights for Black") {
          val initialProb = Evaluator.getOpeningLostCastle.get()
          // Black king moved to d6, White has king at e1 and castling rights (KQ)
          val state = GameState.fromFen("rnbq1bnr/8/3k4/8/8/8/8/4K3 b KQ - 0 1").getOrElse(fail("FEN error"))
          val status = GameStatus.Checkmate(Color.White) // Black won
          
          PassiveTrainer.train(state, status)
          Evaluator.getOpeningLostCastle.get() should be < initialProb
        }

        it("should penalize uncastled king at home without rights for Black") {
          val initialProb = Evaluator.getOpeningUncastled.get()
          // Black king at home but rights gone, White has rights (KQ)
          val state = GameState.fromFen("rnbqkbnr/8/8/8/8/8/8/4K3 b KQ - 0 1").getOrElse(fail("FEN error"))
          val status = GameStatus.Checkmate(Color.White) // Black won
          
          PassiveTrainer.train(state, status)
          Evaluator.getOpeningUncastled.get() should be < initialProb
        }

        it("should update connected rooks bonus for Black") {
          val initialProb = Evaluator.getOpeningConnectedRooks.get()
          // Black rooks at corners a8, h8, nothing between
          val state = GameState.fromFen("r6r/8/8/8/8/8/8/4K3 b - - 0 1").getOrElse(fail("FEN error"))
          val status = GameStatus.Checkmate(Color.White) // Black won
          
          PassiveTrainer.train(state, status)
          Evaluator.getOpeningConnectedRooks.get() should be > initialProb
        }

        it("should penalize missing f and h pawns for Black") {
          val initialProbF = Evaluator.getOpeningWeakPawnF.get()
          // Black pawns missing from f7 and h7. White MUST have pawns on f2, h2.
          val state = GameState.fromFen("4k3/p1p1p1p1/8/8/8/8/5P1P/4K3 b - - 0 1").getOrElse(fail("FEN error"))
          val status = GameStatus.Checkmate(Color.White) // Black won
          
          PassiveTrainer.train(state, status)
          Evaluator.getOpeningWeakPawnF.get() should be < initialProbF
        }
      }
    }
  }
}
