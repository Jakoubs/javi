package chess.rest

import cats.effect.*
import org.mindrot.jbcrypt.BCrypt
import chess.persistence.dao.UserDao
import chess.persistence.model.User
import io.circe.generic.auto.*
import org.http4s.circe.*

case class AuthRequest(username: String, password: String, email: Option[String] = None)
case class AuthResponse(id: Long, username: String, token: String, isVerified: Boolean, emailStatus: String = "")

class AuthService(val userDao: UserDao, emailService: EmailService):
  def register(req: AuthRequest): IO[Either[String, AuthResponse]] =
    val email = req.email.getOrElse("")
    if (email.isEmpty) return IO.pure(Left("Email is required for registration"))
    
    for {
      existingName <- userDao.findByUsername(req.username)
      existingEmail <- userDao.findByEmail(email)
      result <- (existingName, existingEmail) match {
        case (Some(_), _) => IO.pure(Left("Username already exists"))
        case (_, Some(_)) => IO.pure(Left("Email already registered"))
        case (None, None) =>
          val hash = BCrypt.hashpw(req.password, BCrypt.gensalt())
          val verificationToken = java.util.UUID.randomUUID().toString
          val user = User(
            username = req.username,
            email = email,
            passwordHash = hash,
            verificationToken = Some(verificationToken)
          )
          for {
            id <- userDao.save(user)
            emailResult <- emailService.sendVerificationEmail(email, req.username, verificationToken)
            status = emailResult match {
              case Right(msg) => s"Email sent: $msg"
              case Left(err) => s"Email failed: $err"
            }
          } yield Right(AuthResponse(id, req.username, generateToken(id), false, status))
      }
    } yield result

  def login(req: AuthRequest): IO[Either[String, AuthResponse]] =
    for {
      userOpt <- userDao.findByUsername(req.username)
      result = userOpt match {
        case Some(user) if BCrypt.checkpw(req.password, user.passwordHash) =>
          if (user.isVerified) {
            Right(AuthResponse(user.id, user.username, generateToken(user.id), true))
          } else {
            Left("Please verify your email before logging in")
          }
        case _ =>
          Left("Invalid username or password")
      }
    } yield result

  def verify(token: String): IO[Either[String, String]] =
    userDao.verifyUser(token).map {
      case true => Right("Account successfully verified! You can now log in.")
      case false => Left("Invalid or expired verification token.")
    }

  private def generateToken(userId: Long): String =
    java.util.UUID.randomUUID().toString
