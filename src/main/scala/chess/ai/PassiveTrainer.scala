package chess.ai

import chess.model.*

/**
 * Learns from completed games by adjusting evaluation weights.
 */
object PassiveTrainer:

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
    // Simple approach: for each piece on the board, adjust its weight
    // if White wins (reward > 0), increase weight of White pieces, etc.
    // However, it's better to adjust the global weights.
    
    val pieces = game.board.pieces.values.toList
    for piece <- pieces do
      val adjustment = reward * (if piece.color == Color.White then 1.0 else -1.0) * learningRate
      Evaluator.updateWeights(adjustment, piece.pieceType)
