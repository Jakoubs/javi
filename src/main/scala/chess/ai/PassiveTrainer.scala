package chess.ai

import chess.model.*

/**
 * Interface for AI training strategies.
 */
trait Trainer:
  def train(game: GameState, status: GameStatus): Unit

/**
 * Learns from completed games by adjusting evaluation weights.
 */
object PassiveTrainer extends Trainer:

  private val learningRate = 0.5

  /**
   * Called when a game is finished.
   * Adjusts material weights based on the outcome.
   */
  def train(game: GameState, status: GameStatus): Unit =
    status match
      case GameStatus.Checkmate(loser) =>
        val winner = loser.opposite
        val reward = if winner == Color.White then 1.0 else -1.0
        updateWeights(game, reward)
      case GameStatus.Draw(_) | GameStatus.Stalemate =>
        // In case of a draw, we don't necessarily update or we update towards 0
        ()
      case _ => ()

  private def updateWeights(game: GameState, reward: Double): Unit =
    for (pos, piece) <- game.board.pieces do
      val adjustment = reward * (if piece.color == Color.White then 1.0 else -1.0) * learningRate
      Evaluator.updateWeights(adjustment, piece.pieceType, piece.color, Some(pos))
    // Note: saveWeights() is called once by the trainer after all games complete
