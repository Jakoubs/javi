package chess.rest

import cats.effect.*
import cats.implicits.*
import chess.persistence.PersistenceModule
import chess.persistence.model.User
import org.mindrot.jbcrypt.BCrypt

class UserSeeder(persistence: PersistenceModule):

  def seed(): IO[Unit] =
    for {
      _ <- IO.println("Starting user seeding...")
      
      // Create Users
      magnusId <- createOrGetUser("magnus", "magnus@carlsen.no", "kingmagnus", verified = true)
      hikaruId <- createOrGetUser("hikaru", "hikaru@gm.com", "speedchess", verified = true)
      fabianoId <- createOrGetUser("fabiano", "fabiano@gm.com", "caruana", verified = true)
      beginnerId <- createOrGetUser("beginner", "noob@chess.com", "123456", verified = false)
      cheaterId <- createOrGetUser("cheater", "hacker@darkweb.com", "pwned", verified = true, banned = true)

      _ <- IO.println("Users created. Setting up friendships...")

      // Set up Friendships
      _ <- establishFriendship(magnusId, hikaruId, accepted = true)
      _ <- establishFriendship(hikaruId, fabianoId, accepted = true)
      _ <- establishFriendship(magnusId, fabianoId, accepted = false) // Pending from magnus to fabiano
      _ <- establishFriendship(beginnerId, magnusId, accepted = false) // Pending from beginner to magnus

      _ <- IO.println("User seeding completed successfully.")
    } yield ()

  private def createOrGetUser(username: String, email: String, password: String, verified: Boolean, banned: Boolean = false): IO[Long] =
    persistence.userDao.findByUsername(username).flatMap {
      case Some(u) => 
        IO.println(s"User '$username' already exists.") *> IO.pure(u.id)
      case None =>
        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        val user = User(
          username = username,
          email = email,
          passwordHash = hash,
          isVerified = verified,
          isBanned = banned
        )
        persistence.userDao.save(user).flatTap { id =>
          IO.println(s"Created user '$username' with ID $id (Verified: $verified, Banned: $banned)")
        }
    }

  private def establishFriendship(fromId: Long, toId: Long, accepted: Boolean): IO[Unit] =
    for {
      _ <- persistence.friendshipDao.addFriend(fromId, toId)
      _ <- if (accepted) persistence.friendshipDao.acceptFriend(toId, fromId) else IO.unit
      status = if (accepted) "Accepted" else "Pending"
      _ <- IO.println(s"Friendship: $fromId -> $toId ($status)")
    } yield ()
