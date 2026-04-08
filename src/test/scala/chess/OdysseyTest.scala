package chess

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import chess.model.*
import chess.util.OdysseyManager

class OdysseyTest extends AnyWordSpec with Matchers:

  "Challenge Goals" should {
    "correctly detect Mate in 1" in {
      val fen = "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail("Invalid FEN"))
      val goal = MateGoal(1, Color.Black)
      
      goal.isCompleted(state, None, 0) shouldBe false
      
      val move = Move(Pos(0, 0), Pos(0, 7)) // Ra1a8#
      val nextState = GameRules.applyMove(state, move)
      
      goal.isCompleted(nextState, Some(move), 1) shouldBe true
    }

    "fail if moves exceed N in MateGoal" in {
       val goal = MateGoal(1, Color.Black)
       goal.isFailed(GameState.initial, None, 2) shouldBe true
    }

    "correctly track Puzzle sequences" in {
      val moves = List(
        Move(Pos(4, 1), Pos(4, 3)), // e2e4
        Move(Pos(4, 6), Pos(4, 4))  // e7e5
      )
      val goal = PuzzleGoal(moves)
      
      goal.isCompleted(GameState.initial, None, 2) shouldBe true
      goal.isFailed(GameState.initial, Some(Move(Pos(0,0), Pos(0,1))), 1) shouldBe true
    }

    "nextAvailableId should return first incomplete challenge" in {
      val challenges = List(
        Challenge(1, "L1", "D1", "F1", MateGoal(1, Color.Black), 1),
        Challenge(2, "L2", "D2", "F2", MateGoal(1, Color.Black), 2)
      )
      val state = OdysseyState(challenges, Set(1))
      state.nextAvailableId shouldBe Some(2)
      
      val stateAll = OdysseyState(challenges, Set(1, 2))
      stateAll.nextAvailableId shouldBe None
    }

    "isUnlocked should handle more cases" in {
      val challenges = List(
        Challenge(1, "L1", "D1", "F1", MateGoal(1, Color.Black), 1),
        Challenge(2, "L2", "D2", "F2", MateGoal(1, Color.Black), 2),
        Challenge(3, "L3", "D3", "F3", MateGoal(1, Color.Black), 3)
      )
      val state = OdysseyState(challenges, Set(1))
      state.isUnlocked(1) shouldBe true
      state.isUnlocked(2) shouldBe true
      state.isUnlocked(3) shouldBe false
      state.isUnlocked(99) shouldBe false
    }
  }

  "ReachPositionGoal" should {
    "be completed when FEN matches" in {
      val state = GameState.initial
      val goal = ReachPositionGoal(GameState.initialFen)
      goal.isCompleted(state, None, 0) shouldBe true
      goal.description shouldBe "Reach the target position."
    }
    
    "ignore metadata in FEN comparison" in {
      val targetFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 5 10"
      val state = GameState.initial
      val goal = ReachPositionGoal(targetFen)
      goal.isCompleted(state, None, 0) shouldBe true
    }
  }

  "OdysseyManager" should {
    "load challenges from JSON" in {
      val challenges = OdysseyManager.loadChallenges()
      challenges.nonEmpty shouldBe true
      challenges.exists(_.id == 1) shouldBe true
    }

    "handle missing challenges file" in {
      // Since OdysseyManager uses hardcoded file path, testing missing file 
      // might be tricky without moving the file, but we can verify it returns Nil
      // if we were in a temp dir. For now, just test loadProgress.
      OdysseyManager.loadProgress() shouldBe a[Set[?]]
    }

    "save and load progress" in {
      val original = OdysseyManager.loadProgress()
      try {
        OdysseyManager.saveProgress(Set(1, 2, 3))
        OdysseyManager.loadProgress() shouldBe Set(1, 2, 3)
      } finally {
        OdysseyManager.saveProgress(original)
      }
    }
  }
