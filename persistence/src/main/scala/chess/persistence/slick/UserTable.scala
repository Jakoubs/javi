package chess.persistence.slickimpl

import slick.jdbc.JdbcProfile
import chess.persistence.model.{User, Friendship}
import java.time.Instant
import java.sql.Timestamp

class UserTable(val profile: JdbcProfile):
  import profile.api.*

  private implicit val instantType: slick.ast.BaseTypedType[Instant] = MappedColumnType.base[Instant, Timestamp](
    i => Timestamp.from(i),
    t => t.toInstant
  )

  class Users(tag: Tag) extends Table[User](tag, "users"):
    def id           = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def username     = column[String]("username", O.Unique)
    def passwordHash = column[String]("password_hash")
    def createdAt    = column[Instant]("created_at")

    def * = (id, username, passwordHash, createdAt) <> (User.apply.tupled, User.unapply)

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

  def createSchema = (users.schema ++ friendships.schema).createIfNotExists
