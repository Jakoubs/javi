package chess.persistence.model

/**
 * Represents a single move that occurred within a game.
 *
 * @param id         Unique move-event identifier (UUID string)
 * @param gameId     Reference to the parent [[PersistedGame]]
 * @param moveNumber Half-move index (0-based). White's first move = 0, Black's = 1, …
 * @param san        Move in Standard Algebraic Notation, e.g. "e4", "Nf3", "O-O"
 * @param uci        Move in UCI notation, e.g. "e2e4", "g1f3"
 * @param fenAfter   FEN string of the board position after this move
 * @param timestamp  When the move was played (epoch millis)
 */
case class MoveEvent(
  id:         String,
  gameId:     String,
  moveNumber: Int,
  san:        String,
  uci:        String,
  fenAfter:   String,
  timestamp:  Long
)
