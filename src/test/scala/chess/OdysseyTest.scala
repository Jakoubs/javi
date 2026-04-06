package chess

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import chess.model.*
import chess.util.OdysseyManager

class OdysseyTest extends AnyWordSpec with Matchers:

  "Challenge Goals" should {
    "correctly detect Mate in 1" in {
      val fen = "4k3/8/4Q3/8/4K3/8/8/8 w - - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail("Invalid FEN"))
      val goal = MateGoal(1, Color.Black)
      
      goal.isCompleted(state, None, 0) shouldBe false
      
      // Qe6 to d7 is checkmate? No, e6 to e7 is.
      val move = Move(Pos(4, 5), Pos(4, 6)) // Qe6e7#
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
  }

  "OdysseyManager" should {
    "load challenges from JSON" in {
      val challenges = OdysseyManager.loadChallenges()
      challenges.nonEmpty shouldBe true
      challenges.exists(_.id == 1) shouldBe true
    }

    "track progress correctly" in {
      val challenges = List(
        Challenge(1, "L1", "D1", "F1", MateGoal(1, Color.Black), 1),
        Challenge(2, "L2", "D2", "F2", MateGoal(1, Color.Black), 2)
      )
      val state = OdysseyState(challenges, Set(1))
      
      state.isUnlocked(1) shouldBe true
      state.isUnlocked(2) shouldBe true
      state.completedIds should contain (1)
    }
  }
