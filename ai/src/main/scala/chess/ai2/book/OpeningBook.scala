package chess.ai2.book

import chess.model.{GameState, Move}

final case class BookMove(move: Move, weight: Int)

trait OpeningBook:
  def pick(state: GameState): Option[BookMove]

object NoOpeningBook extends OpeningBook:
  override def pick(state: GameState): Option[BookMove] = None

