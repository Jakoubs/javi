package chess.persistence.model

/**
 * Represents a chess game as persisted in the database.
 *
 * @param id         Unique game identifier (UUID string)
 * @param startFen   FEN of the starting position
 * @param finalFen   FEN of the current / final position
 * @param pgn        Full PGN string of the game so far
 * @param result     One of: "white", "black", "draw", "ongoing"
 * @param createdAt  Creation timestamp (epoch millis)
 * @param updatedAt  Last-update timestamp (epoch millis)
 */
case class PersistedGame(
  id:        String,
  startFen:  String,
  finalFen:  String,
  pgn:       String,
  result:    String,
  createdAt: Long,
  updatedAt: Long
)
