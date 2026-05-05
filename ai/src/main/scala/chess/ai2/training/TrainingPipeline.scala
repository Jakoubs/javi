package chess.ai2.training

import chess.model.{GameState, Move}

final case class TrainingSample(
  state: GameState,
  policyTarget: Map[Move, Double],
  valueTarget: Double,
  source: String
)

trait TrainingPipeline:
  def ingestLichessDump(path: String): Long
  def trainStep(batchSize: Int): Unit

