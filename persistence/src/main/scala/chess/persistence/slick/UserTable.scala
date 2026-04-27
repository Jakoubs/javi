package chess.persistence.slick

import _root_.slick.jdbc.JdbcProfile
import chess.persistence.model.{User, Friendship}
import java.time.Instant
import java.sql.Timestamp

class UserTable(val profile: JdbcProfile):
  import profile.api.*

  private implicit val instantType: _root_.slick.ast.BaseTypedType[Instant] = MappedColumnType.base[Instant, Timestamp](
    i => Timestamp.from(i),
    t => t.toInstant
  )

  class Users(tag: Tag) extends Table[User](tag, "users"):
    def id                = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def username          = column[String]("username", O.Unique)
    def email             = column[String]("email", O.Unique)
    def passwordHash      = column[String]("password_hash")
    def isVerified        = column[Boolean]("is_verified", O.Default(false))
    def verificationToken = column[Option[String]]("verification_token")
    def createdAt         = column[Instant]("created_at")

    def * = (id, username, email, passwordHash, isVerified, verificationToken, createdAt) <> (User.apply.tupled, User.unapply)

  val users = TableQuery[Users]

  class Friendships(tag: Tag) extends Table[Friendship](tag, "friendships"):
    def userId    = column[Long]("user_id")
    def friendId  = column[Long]("friend_id")
    def status    = column[String]("status")
    def createdAt = column[Instant]("created_at")

    def pk = primaryKey("pk_friendships", (userId, friendId))
    def userFk   = foreignKey("user_fk", userId, users)(_.id)
    def friendFk = foreignKey("friend_fk", friendId, users)(_.id)

    def * = (userId, friendId, status, createdAt) <> (Friendship.apply.tupled, Friendship.unapply)

  val friendships = TableQuery[Friendships]

  import _root_.slick.jdbc.meta.MTable
  import scala.concurrent.ExecutionContext.Implicits.global
  def createSchema: profile.api.DBIO[Unit] =
    for {
      tUsers <- MTable.getTables("users")
      _      <- if tUsers.isEmpty then users.schema.create else DBIO.successful(())
      tFriends <- MTable.getTables("friendships")
      _      <- if tFriends.isEmpty then friendships.schema.create else DBIO.successful(())
    } yield ()
