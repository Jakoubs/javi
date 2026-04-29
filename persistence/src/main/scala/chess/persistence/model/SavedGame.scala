package chess.persistence.model

import java.time.Instant

/**
 * A game state saved manually by a user.
 * 
 * @param id        UUID
 * @param name      User-provided name
 * @param fen       The position FEN
 * @param pgn       The move history PGN
 * @param userId    Owner of this save
 * @param createdAt Creation time
 */
case class SavedGame(
  id:        String,
  name:      String,
  fen:       String,
  pgn:       String,
  userId:    Long,
  createdAt: Instant = Instant.now()
)
