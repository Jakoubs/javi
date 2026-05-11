package chess.rest

import cats.effect.*
import cats.implicits.*
import chess.lichess.{LichessOpeningExplorer, LichessOpeningMove}
import chess.persistence.PersistenceModule
import chess.persistence.model.Opening
import chess.model.{GameState, GameRules}
import chess.util.parser.CoordinateMoveParser

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/**
 * Utility to seed the local database with opening data from Lichess.
 * 
 * It crawls the opening tree recursively up to a certain depth and saves
 * the top moves for each position.
 */
class OpeningSeeder(explorer: LichessOpeningExplorer, persistence: PersistenceModule)(using ec: ExecutionContext):

  private def fenParts(fen: String): Array[String] =
    fen.trim.split("\\s+")

  // Keep piece placement + turn + castling rights + en-passant (first 4 FEN fields)
  private def fenCoreKey(fen: String): Option[String] =
    val parts = fenParts(fen)
    if parts.length >= 4 then Some(parts.take(4).mkString(" ")) else None

  // Keep piece placement + turn (first 2 FEN fields)
  private def fenBoardTurnKey(fen: String): Option[String] =
    val parts = fenParts(fen)
    if parts.length >= 2 then Some(parts.take(2).mkString(" ")) else None

  /**
   * Run the seeding process.
   * 
   * @param maxDepth Max number of half-moves from start.
   * @param maxMovesPerPos Number of top moves to follow per position.
   */
  def seed(maxDepth: Int, maxMovesPerPos: Int): IO[Unit] =
    for {
      _ <- IO.println(s"Starting opening seeding (maxDepth=$maxDepth, branch=$maxMovesPerPos)...")
      _ <- fetchRecursive(GameState.initial, maxDepth, maxMovesPerPos)
      count <- persistence.openingDao.count()
      _ <- IO.println(s"Seeding completed. Total openings in DB: $count")
    } yield ()

  private def fetchRecursive(state: GameState, depth: Int, branch: Int): IO[Unit] =
    if depth < 0 then IO.unit
    else
      IO.fromFuture(IO(explorer.getOpenings(state.toFen))).flatMap {
        case None => IO.unit
        case Some(res) =>
          val movesToFetch = res.moves.take(branch)
          movesToFetch.traverse { m =>
            val fenExact = state.toFen
            val coreKeyOpt = fenCoreKey(fenExact)
            val boardKeyOpt = fenBoardTurnKey(fenExact)

            val openingsToSave = (
              List((fenExact, "exact")) ++
                coreKeyOpt.map(k => (k, "fen-core")).toList ++
                boardKeyOpt.map(k => (k, "fen-board+turn")).toList
            // avoid pointless duplicates (e.g. if normalization produces same string)
            ).distinctBy(_._1)

            val opening = Opening(
              fen = fenExact,
              move = m.uci,
              name = res.opening.map(_.name),
              weight = m.white + m.draws + m.black
            )
            
            for {
              _ <- openingsToSave.traverse { (fenKey, _) =>
                persistence.openingDao.save(
                  opening.copy(fen = fenKey)
                )
              }
              _ <- IO.println(s"Saved: ${opening.name.getOrElse("?")} | ${opening.move} @${openingsToSave.size} fen-keys (depth remaining: $depth)")
              
              // Apply move to get next FEN
              _ <- CoordinateMoveParser.parse(m.uci, state).toOption match {
                case Some(move) =>
                  val nextState = GameRules.applyMove(state, move)
                  // Recursive call
                  // Throttle to respect Lichess API limits (approx 1 req/sec is safe)
                  IO.sleep(200.milliseconds) *> fetchRecursive(nextState, depth - 1, branch)
                case None => IO.unit
              }
            } yield ()
          }.void
      }
