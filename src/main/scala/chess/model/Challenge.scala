package chess.model

import chess.util.Result

/**
 * Strategy trait for different challenge completion logic.
 */
trait ChallengeGoal:
  def isCompleted(state: GameState, lastMove: Option[Move], moveCount: Int): Boolean
  def isFailed(state: GameState, lastMove: Option[Move], moveCount: Int): Boolean = false
  def description: String

/**
 * Mate in N moves.
 */
case class MateGoal(n: Int, loser: Color) extends ChallengeGoal:
  def isCompleted(state: GameState, lastMove: Option[Move], moveCount: Int): Boolean =
    GameRules.computeStatus(state) == GameStatus.Checkmate(loser) && moveCount <= n
  
  override def isFailed(state: GameState, lastMove: Option[Move], moveCount: Int): Boolean =
    moveCount >= n && GameRules.computeStatus(state) != GameStatus.Checkmate(loser)

  def description: String = s"Checkmate ${loser} in $n moves."

/**
 * Reach a specific FEN position.
 */
case class ReachPositionGoal(targetFen: String) extends ChallengeGoal:
  def isCompleted(state: GameState, lastMove: Option[Move], moveCount: Int): Boolean =
    state.toFen.split(" ").take(4).mkString(" ") == targetFen.split(" ").take(4).mkString(" ")
  
  def description: String = "Reach the target position."

/**
 * Solve a puzzle by following a sequence of moves.
 */
case class PuzzleGoal(moves: List[Move]) extends ChallengeGoal:
  def isCompleted(state: GameState, lastMove: Option[Move], moveCount: Int): Boolean =
    moveCount >= moves.size

  override def isFailed(state: GameState, lastMove: Option[Move], moveCount: Int): Boolean =
    lastMove.exists(m => moveCount <= moves.size && m != moves(moveCount - 1))

  def description: String = s"Follow the correct sequence of ${moves.size} moves."

/**
 * Base Challenge class.
 */
case class Challenge(
  id: Int,
  name: String,
  description: String,
  initialFen: String,
  goal: ChallengeGoal,
  difficulty: Int
)

/**
 * Current state of the Odyssey.
 */
case class OdysseyState(
  challenges: List[Challenge],
  completedIds: Set[Int],
  currentChallenge: Option[Challenge] = None,
  currentMoveCount: Int = 0,
  failedAttempts: Int = 0
):
  def isUnlocked(challengeId: Int): Boolean =
    val challenge = challenges.find(_.id == challengeId)
    challenge.exists(c => c.id == 1 || completedIds.contains(c.id - 1))

  def nextAvailableId: Option[Int] =
    challenges.find(c => !completedIds.contains(c.id)).map(_.id)
