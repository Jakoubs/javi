package chess.persistence.slick

import _root_.slick.jdbc.JdbcProfile
import chess.persistence.model.PuzzleTheme

/**
 * Slick `Table` mapping for [[PuzzleTheme]].
 */
class PuzzleThemeTable(val profile: JdbcProfile):
  import profile.api.*

  class PuzzleThemes(tag: Tag) extends Table[PuzzleTheme](tag, "puzzle_themes"):
    def key         = column[String]("key", O.PrimaryKey)
    def name        = column[String]("name")
    def description = column[String]("description")

    def * = (key, name, description).mapTo[PuzzleTheme]

  val puzzleThemes = TableQuery[PuzzleThemes]

  import _root_.slick.jdbc.meta.MTable
  import scala.concurrent.ExecutionContext.Implicits.global

  /** DDL to create the `puzzle_themes` table if it does not exist. */
  val createSchema: profile.api.DBIO[Unit] =
    MTable.getTables("puzzle_themes").flatMap { tables =>
      if tables.isEmpty then puzzleThemes.schema.create
      else DBIO.successful(())
    }
