package chess.persistence.model

/**
 * Represents a chess opening position and a recommended move.
 *
 * @param fen    The FEN of the position (key).
 * @param move   The recommended move in coordinate notation (e.g., "e2e4").
 * @param name   The name of the opening (optional).
 * @param weight The priority/weight of this move (e.g., game count or score).
 */
case class Opening(
  fen:    String,
  move:   String,
  name:   Option[String] = None,
  weight: Int = 1
)
