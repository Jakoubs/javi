package chess.ai2.tablebase

import chess.model.{GameState, Move}

final case class TablebaseHit(bestMove: Move, wdl: Int, dtz: Option[Int], dtm: Option[Int])

trait EndgameOracle:
  def probe(state: GameState): Option[TablebaseHit]

object NoEndgameOracle extends EndgameOracle:
  override def probe(state: GameState): Option[TablebaseHit] = None

