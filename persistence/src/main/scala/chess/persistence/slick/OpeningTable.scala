package chess.persistence.slick

import _root_.slick.jdbc.JdbcProfile
import chess.persistence.model.Opening

/**
 * Slick `Table` mapping for [[Opening]].
 */
class OpeningTable(val profile: JdbcProfile):
  import profile.api.*

  class Openings(tag: Tag) extends Table[Opening](tag, "openings"):
    def fen    = column[String]("fen")
    def move   = column[String]("move")
    def name   = column[Option[String]]("name")
    def weight = column[Int]("weight")

    def pk = primaryKey("pk_openings", (fen, move))
    def idxFen = index("idx_openings_fen", fen)

    def * = (fen, move, name, weight).mapTo[Opening]

  val openings = TableQuery[Openings]

  import _root_.slick.jdbc.meta.MTable
  import scala.concurrent.ExecutionContext.Implicits.global
  /** DDL to create the `openings` table if it does not exist. */
  val createSchema: profile.api.DBIO[Unit] =
    MTable.getTables("openings").flatMap { tables =>
      if tables.isEmpty then openings.schema.create
      else DBIO.successful(())
    }
