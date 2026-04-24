package chess.persistence.dao

import cats.effect.IO
import chess.persistence.model.{User, Friendship}

trait UserDao:
  def save(user: User): IO[Long]
  def findByUsername(username: String): IO[Option[User]]
  def findById(id: Long): IO[Option[User]]

trait FriendshipDao:
  def addFriend(userId: Long, friendId: Long): IO[Unit]
  def getFriends(userId: Long): IO[List[User]]
  def acceptFriend(userId: Long, friendId: Long): IO[Unit]
