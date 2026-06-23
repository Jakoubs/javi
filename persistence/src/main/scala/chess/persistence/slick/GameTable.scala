package chess.persistence.slick

import _root_.slick.jdbc.JdbcProfile
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
    def id          = column[String]("id", O.PrimaryKey)
    def startFen    = column[String]("start_fen")
    def finalFen    = column[String]("final_fen")
    def pgn         = column[String]("pgn")
    def result      = column[String]("result")
    def createdAt   = column[Long]("created_at")
    def updatedAt   = column[Long]("updated_at")
    def whitePlayer = column[String]("white_player", O.Default("guest"))
    def blackPlayer = column[String]("black_player", O.Default("guest"))

    def * = (id, startFen, finalFen, pgn, result, createdAt, updatedAt, whitePlayer, blackPlayer)
      .mapTo[PersistedGame]

  val games = TableQuery[Games]

  import _root_.slick.jdbc.meta.MTable
  import scala.concurrent.ExecutionContext.Implicits.global
  /** DDL to create the `games` table if it does not exist. */
  val createSchema: profile.api.DBIO[Unit] =
    MTable.getTables.flatMap { tables =>
      if !tables.exists(_.name.name.equalsIgnoreCase("games")) then games.schema.create
      else DBIO.successful(())
    }
