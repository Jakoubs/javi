package chess.persistence.slick

import _root_.slick.jdbc.JdbcProfile
import chess.persistence.model.TablebaseEntry

class TablebaseTable(val profile: JdbcProfile):
  import profile.api.*

  class TablebaseEntries(tag: Tag) extends Table[TablebaseEntry](tag, "tablebase_entries"):
    def fen      = column[String]("fen", O.PrimaryKey)
    def bestMove = column[String]("best_move")
    def wdl      = column[Int]("wdl")
    def dtz      = column[Option[Int]]("dtz")
    def dtm      = column[Option[Int]]("dtm")
    def source   = column[String]("source")

    def * = (fen, bestMove, wdl, dtz, dtm, source).mapTo[TablebaseEntry]

  val tablebaseEntries = TableQuery[TablebaseEntries]

  import _root_.slick.jdbc.meta.MTable
  import scala.concurrent.ExecutionContext.Implicits.global
  val createSchema: profile.api.DBIO[Unit] =
    MTable.getTables("tablebase_entries").flatMap { tables =>
      if tables.isEmpty then tablebaseEntries.schema.create
      else DBIO.successful(())
    }
