package chess.persistence.slick

import _root_.slick.jdbc.JdbcProfile
import chess.persistence.model.SavedGame
import java.time.Instant
import java.sql.Timestamp

class SavedGameTable(val profile: JdbcProfile):
  import profile.api.*

  private implicit val instantType: _root_.slick.ast.BaseTypedType[Instant] = MappedColumnType.base[Instant, Timestamp](
    i => Timestamp.from(i),
    t => t.toInstant
  )

  class SavedGames(tag: Tag) extends Table[SavedGame](tag, "saved_games"):
    def id        = column[String]("id", O.PrimaryKey)
    def name      = column[String]("name")
    def fen       = column[String]("fen")
    def pgn       = column[String]("pgn")
    def userId    = column[Long]("user_id")
    def createdAt = column[Instant]("created_at")

    def * = (id, name, fen, pgn, userId, createdAt) <> (SavedGame.apply.tupled, SavedGame.unapply)

  val savedGames = TableQuery[SavedGames]

  import _root_.slick.jdbc.meta.MTable
  import scala.concurrent.ExecutionContext.Implicits.global

  def createSchema: profile.api.DBIO[Unit] =
    MTable.getTables("saved_games").flatMap { tables =>
      if tables.isEmpty then savedGames.schema.create
      else DBIO.successful(())
    }
