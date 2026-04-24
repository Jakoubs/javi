package chess.rest

import cats.effect.*
import org.mindrot.jbcrypt.BCrypt
import chess.persistence.dao.UserDao
import chess.persistence.model.User
import io.circe.generic.auto.*
import org.http4s.circe.*

case class AuthRequest(username: String, password: String)
case class AuthResponse(id: Long, username: String, token: String)

class AuthService(userDao: UserDao):
  def register(req: AuthRequest): IO[Either[String, AuthResponse]] =
    for {
      existing <- userDao.findByUsername(req.username)
      result <- existing match {
        case Some(_) => IO.pure(Left("Username already exists"))
        case None =>
          val hash = BCrypt.hashpw(req.password, BCrypt.gensalt())
          userDao.save(User(username = req.username, passwordHash = hash)).map { id =>
            Right(AuthResponse(id, req.username, generateToken(id)))
          }
      }
    } yield result

  def login(req: AuthRequest): IO[Either[String, AuthResponse]] =
    for {
      userOpt <- userDao.findByUsername(req.username)
      result = userOpt match {
        case Some(user) if BCrypt.checkpw(req.password, user.passwordHash) =>
          Right(AuthResponse(user.id, user.username, generateToken(user.id)))
        case _ =>
          Left("Invalid username or password")
      }
    } yield result

  private def generateToken(userId: Long): String =
    // Simple mock token for now (in production use JWT/TSEC)
    java.util.UUID.randomUUID().toString
