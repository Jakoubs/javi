package chess.ai.nn

import chess.model.{GameState, Move}

final case class PolicyValueEvaluation(
  priors: Map[Move, Double],
  value: Double,
  uncertainty: Double
)

trait PolicyValueNet:
  def evaluate(state: GameState, legalMoves: List[Move]): PolicyValueEvaluation

  def evaluateBatch(inputs: List[(GameState, List[Move])]): List[PolicyValueEvaluation] =
    inputs.map { case (state, legalMoves) => evaluate(state, legalMoves) }

