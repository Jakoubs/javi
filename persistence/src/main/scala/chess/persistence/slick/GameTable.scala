package chess.persistence.slickimpl

import slick.jdbc.JdbcProfile
import chess.persistence.model.PersistedGame

/**
 * Slick `Table` mapping for [[PersistedGame]].
 *
 * The `profile` is injected so the same mapping can be used with both
 * PostgreSQL (`PostgresProfile`) and H2 (`H2Profile`) in tests.
 */
class GameTable(val profile: JdbcProfile):
  import profile.api.*

  class Games(tag: Tag) extends Table[PersistedGame](tag, "games"):
    def id        = column[String]("id", O.PrimaryKey)
    def startFen  = column[String]("start_fen")
    def finalFen  = column[String]("final_fen")
    def pgn       = column[String]("pgn")
    def result    = column[String]("result")
    def createdAt = column[Long]("created_at")
    def updatedAt = column[Long]("updated_at")

    def * = (id, startFen, finalFen, pgn, result, createdAt, updatedAt)
      .mapTo[PersistedGame]

  val games = TableQuery[Games]

  import slick.jdbc.meta.MTable
  import scala.concurrent.ExecutionContext.Implicits.global
  /** DDL to create the `games` table if it does not exist. */
  val createSchema: profile.api.DBIO[Unit] =
    MTable.getTables("games").flatMap { tables =>
      if tables.isEmpty then games.schema.create
      else DBIO.successful(())
    }
