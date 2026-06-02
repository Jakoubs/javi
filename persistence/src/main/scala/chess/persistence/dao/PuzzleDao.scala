package chess.persistence.dao

import cats.effect.IO
import chess.persistence.model.{Puzzle, PuzzleTheme}

/**
 * Data Access Object for chess puzzles and puzzle themes.
 */
trait PuzzleDao:

  /** Save a puzzle (upsert by id). */
  def save(puzzle: Puzzle): IO[Unit]

  /** Save multiple puzzles in one batch. */
  def saveBatch(puzzles: List[Puzzle]): IO[Unit]

  /** Return a random puzzle, optionally filtered by theme. */
  def getRandom(theme: Option[String] = None): IO[Option[Puzzle]]

  /**
   * Find puzzles by theme, sorted by rating.
   *
   * @param theme  The theme key to filter by.
   * @param desc   If true, sort by rating descending (hardest first). Otherwise ascending.
   * @param limit  Maximum number of puzzles to return.
   * @param offset Offset for pagination.
   */
  def findByTheme(theme: String, desc: Boolean = false, limit: Int = 20, offset: Int = 0): IO[List[Puzzle]]

  /** Count the total number of puzzles in the database. */
  def countPuzzles(): IO[Long]

  // ─── Theme operations ────────────────────────────────────────────────────

  /** Save a puzzle theme (upsert by key). */
  def saveTheme(theme: PuzzleTheme): IO[Unit]

  /** Return all available puzzle themes. */
  def findAllThemes(): IO[List[PuzzleTheme]]
