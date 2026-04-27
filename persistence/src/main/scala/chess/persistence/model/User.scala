package chess.persistence.model

import java.time.Instant

case class User(
  id:                Long           = 0L,
  username:          String,
  email:             String,
  passwordHash:      String,
  isVerified:        Boolean        = false,
  verificationToken: Option[String] = None,
  createdAt:         Instant        = Instant.now()
)

case class Friendship(
  userId:    Long,
  friendId:  Long,
  status:    String, // "pending", "accepted"
  createdAt: Instant = Instant.now()
)
