package chess.persistence.model

/**
 * Represents a chess puzzle from the Lichess database.
 *
 * @param id       The unique Lichess puzzle ID (e.g. "00sHx").
 * @param fen      The FEN of the position *before* the opponent's first move.
 * @param solution The list of moves in UCI format. First move is the opponent's, second is the player's first correct move, etc.
 * @param rating   The Glicko-2 rating of the puzzle.
 * @param themes   The list of theme keys (e.g. "mate", "mateIn2", "short").
 */
case class Puzzle(
  id:       String,
  fen:      String,
  solution: List[String],
  rating:   Int,
  themes:   List[String]
)
