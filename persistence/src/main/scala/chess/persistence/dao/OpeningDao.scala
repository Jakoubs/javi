package chess.persistence.dao

import cats.effect.IO
import chess.persistence.model.Opening

/**
 * Data Access Object for chess openings.
 */
trait OpeningDao:
  /** Find all moves for a given FEN. */
  def findByFen(fen: String): IO[List[Opening]]

  /** Find all moves for a FEN core (first 4 FEN fields). */
  def findByFenCore(fenCore: String): IO[List[Opening]]

  /** Find all moves by board placement + side to move (first 2 FEN fields). */
  def findByFenBoardTurn(fenBoardTurn: String): IO[List[Opening]]
  
  /** Save an opening move (upsert). */
  def save(opening: Opening): IO[Unit]
  
  /** Count the total number of openings in the database. */
  def count(): IO[Long]
  
  /** Delete all openings (useful for re-seeding). */
  def deleteAll(): IO[Unit]
