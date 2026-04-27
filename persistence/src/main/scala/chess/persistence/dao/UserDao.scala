package chess.persistence.dao

import cats.effect.IO
import chess.persistence.model.{User, Friendship}

trait UserDao:
  def save(user: User): IO[Long]
  def findByUsername(username: String): IO[Option[User]]
  def findByEmail(email: String): IO[Option[User]]
  def findByVerificationToken(token: String): IO[Option[User]]
  def verifyUser(token: String): IO[Boolean]
  def findById(id: Long): IO[Option[User]]

trait FriendshipDao:
  def addFriend(userId: Long, friendId: Long): IO[Unit]
  def getFriends(userId: Long): IO[List[User]]
  def acceptFriend(userId: Long, friendId: Long): IO[Unit]
  def getPendingRequests(userId: Long): IO[List[User]]

