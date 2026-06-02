package chess.persistence.slick

import _root_.slick.jdbc.JdbcProfile
import chess.persistence.model.MoveEvent

/**
 * Slick `Table` mapping for [[MoveEvent]].
 *
 * The `game_id` column carries a foreign-key reference to `games(id)`,
 * with cascade-delete so that removing a game removes all its move events.
 */
class MoveEventTable(val profile: JdbcProfile):
  import profile.api.*

  class MoveEvents(tag: Tag) extends Table[MoveEvent](tag, "move_events"):
    def id         = column[String]("id", O.PrimaryKey)
    def gameId     = column[String]("game_id")
    def moveNumber = column[Int]("move_number")
    def san        = column[String]("san")
    def uci        = column[String]("uci")
    def fenAfter   = column[String]("fen_after")
    def timestamp  = column[Long]("timestamp")

    // Index on game_id for fast look-ups of all moves in a game
    def gameIdIdx = index("idx_move_events_game_id", gameId)

    def * = (id, gameId, moveNumber, san, uci, fenAfter, timestamp)
      .mapTo[MoveEvent]

  val moveEvents = TableQuery[MoveEvents]

  import _root_.slick.jdbc.meta.MTable
  import scala.concurrent.ExecutionContext.Implicits.global
  /** DDL to create the `move_events` table if it does not exist. */
  val createSchema: profile.api.DBIO[Unit] =
    MTable.getTables("move_events").flatMap { tables =>
      if tables.isEmpty then moveEvents.schema.create
      else DBIO.successful(())
    }
