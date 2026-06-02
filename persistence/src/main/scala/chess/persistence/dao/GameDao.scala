package chess.persistence.dao

import cats.effect.IO
import chess.persistence.model.PersistedGame

/**
 * DAO interface for [[PersistedGame]] entities.
 *
 * All operations are expressed in `cats.effect.IO` so that both the Slick
 * (which internally uses Futures) and the MongoDB (which uses Reactive Streams)
 * implementations share a unified, composable effect type.
 *
 * Callers only depend on this trait; the concrete implementation
 * (Slick or Mongo) is selected at startup via [[chess.persistence.PersistenceModule]].
 */
trait GameDao:

  /** Persist a new game record. */
  def save(game: PersistedGame): IO[Unit]

  /** Look up a game by its UUID. Returns `None` if not found. */
  def findById(id: String): IO[Option[PersistedGame]]

  /** Return all persisted games, newest first. */
  def findAll(): IO[List[PersistedGame]]

  /**
   * Overwrite an existing game record (identified by `game.id`).
   * Typically used to update `finalFen`, `pgn`, `result`, and `updatedAt`.
   */
  def update(game: PersistedGame): IO[Unit]

  /** Remove a game and (cascading) all its associated [[chess.persistence.model.MoveEvent]]s. */
  def delete(id: String): IO[Unit]
