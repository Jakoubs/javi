package chess.persistence.model

/**
 * Represents a puzzle theme (tactic category) from Lichess.
 *
 * @param key         The machine-readable key (e.g. "backRankMate").
 * @param name        The human-readable name (e.g. "Back rank mate").
 * @param description A short explanation of the theme.
 */
case class PuzzleTheme(
  key:         String,
  name:        String,
  description: String
)
