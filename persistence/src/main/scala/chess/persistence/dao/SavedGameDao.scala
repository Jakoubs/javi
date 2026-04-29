package chess.persistence.dao

import cats.effect.IO
import chess.persistence.model.SavedGame

trait SavedGameDao:
  def save(game: SavedGame): IO[Unit]
  def findByUserId(userId: Long): IO[List[SavedGame]]
  def findSavedGameById(id: String): IO[Option[SavedGame]]
  def delete(id: String, userId: Long): IO[Unit]
