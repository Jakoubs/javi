package chess.persistence.dao

import cats.effect.IO
import chess.persistence.model.MoveEvent

/**
 * DAO interface for [[MoveEvent]] entities.
 *
 * A `MoveEvent` records a single half-move made within a game.  Games may be
 * replayed move-by-move by loading all events for a given `gameId` in order.
 *
 * Like [[GameDao]], all operations are expressed in `cats.effect.IO`.
 */
trait MoveEventDao:

  /** Persist a single move event. */
  def save(event: MoveEvent): IO[Unit]

  /**
   * Return all move events for the given game, ordered by [[MoveEvent.moveNumber]]
   * ascending.
   */
  def findByGameId(gameId: String): IO[List[MoveEvent]]

  /**
   * Delete all move events belonging to a game.
   * Called automatically from [[GameDao.delete]] in the Slick implementation
   * (or handled by cascading deletes in the DB schema).
   */
  def deleteByGameId(gameId: String): IO[Unit]
