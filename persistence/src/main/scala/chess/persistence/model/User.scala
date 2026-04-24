package chess.persistence.model

import java.time.Instant

case class User(
  id:           Long           = 0L,
  username:     String,
  passwordHash: String,
  createdAt:    Instant        = Instant.now()
)

case class Friendship(
  userId:    Long,
  friendId:  Long,
  status:    String, // "pending", "accepted"
  createdAt: Instant = Instant.now()
)
