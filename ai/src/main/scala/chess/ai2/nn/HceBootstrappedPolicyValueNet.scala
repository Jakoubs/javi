package chess.ai2.nn

import chess.ai.Evaluator
import chess.model.{Color, GameState, Move}

class HceBootstrappedPolicyValueNet extends PolicyValueNet:
  override def evaluate(state: GameState, legalMoves: List[Move]): PolicyValueEvaluation =
    val normalized = if legalMoves.nonEmpty then 1.0 / legalMoves.size.toDouble else 0.0
    val priors = legalMoves.iterator.map(m => m -> normalized).toMap
    val side = if state.activeColor == Color.White then 1.0 else -1.0
    val value = math.tanh((Evaluator.evaluate(state) * side) / 600.0)
    PolicyValueEvaluation(priors = priors, value = value, uncertainty = 0.2)

object HceBootstrappedPolicyValueNet:
  val default: HceBootstrappedPolicyValueNet = new HceBootstrappedPolicyValueNet()

