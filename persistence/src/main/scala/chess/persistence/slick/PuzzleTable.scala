package chess.persistence.slick

import _root_.slick.jdbc.JdbcProfile
import chess.persistence.model.Puzzle

/**
 * Slick `Table` mapping for [[Puzzle]].
 *
 * The `themes` and `solution` columns are stored as comma-separated strings
 * in the database. Conversion to/from `List[String]` happens in the `*` projection.
 */
class PuzzleTable(val profile: JdbcProfile):
  import profile.api.*

  class Puzzles(tag: Tag) extends Table[Puzzle](tag, "puzzles"):
    def id       = column[String]("id", O.PrimaryKey)
    def fen      = column[String]("fen")
    def solution = column[String]("solution")
    def rating   = column[Int]("rating")
    def themes   = column[String]("themes")

    def idxRating = index("idx_puzzles_rating", rating)

    def * = (id, fen, solution, rating, themes).<>(
      { case (id, fen, sol, rat, th) =>
        Puzzle(id, fen, sol.split(",").toList, rat, th.split(",").filter(_.nonEmpty).toList)
      },
      { (p: Puzzle) =>
        Some((p.id, p.fen, p.solution.mkString(","), p.rating, p.themes.mkString(",")))
      }
    )

  val puzzles = TableQuery[Puzzles]

  import _root_.slick.jdbc.meta.MTable
  import scala.concurrent.ExecutionContext.Implicits.global

  /** DDL to create the `puzzles` table if it does not exist. */
  val createSchema: profile.api.DBIO[Unit] =
    MTable.getTables("puzzles").flatMap { tables =>
      if tables.isEmpty then puzzles.schema.create
      else DBIO.successful(())
    }
