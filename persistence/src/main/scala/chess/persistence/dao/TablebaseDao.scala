package chess.persistence.dao

import cats.effect.IO
import chess.persistence.model.TablebaseEntry

trait TablebaseDao:
  def findEntryByFen(fen: String): IO[Option[TablebaseEntry]]
  def saveEntry(entry: TablebaseEntry): IO[Unit]
  def countEntries(): IO[Long]
