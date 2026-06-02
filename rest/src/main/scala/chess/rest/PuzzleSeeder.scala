package chess.rest

import cats.effect.*
import cats.implicits.*
import chess.persistence.PersistenceModule
import chess.persistence.model.{Puzzle, PuzzleTheme}

import java.io.{BufferedReader, FileReader}
import scala.collection.mutable.ListBuffer

/**
 * Utility to seed the local database with puzzle data from Lichess.
 *
 * Supports two seeding modes:
 *   1. '''CSV Mode''': Read from a local CSV file downloaded from `database.lichess.org`.
 *   2. '''Theme Mode''': Parse the hardcoded puzzle theme definitions.
 *
 * CSV format (from Lichess):
 * {{{
 * PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags
 * }}}
 */
class PuzzleSeeder(persistence: PersistenceModule):

  private val BATCH_SIZE = 500

  /**
   * Seed puzzles from a local CSV file.
   *
   * @param csvPath Path to the uncompressed `lichess_db_puzzle.csv`.
   * @param limit   Maximum number of puzzles to import (0 = all).
   */
  def seedFromCsv(csvPath: String, limit: Int = 0): IO[Unit] =
    IO.blocking {
      val reader = new BufferedReader(new FileReader(csvPath))
      // Skip header
      reader.readLine()
      (reader, 0, 0)
    }.flatMap { case (reader, _, _) =>
      def processBatch(imported: Int): IO[Int] =
        IO.blocking {
          val batch = ListBuffer.empty[Puzzle]
          var line  = reader.readLine()
          while (line != null && batch.size < BATCH_SIZE && (limit == 0 || imported + batch.size < limit)) {
            parseCsvLine(line).foreach(batch += _)
            line = reader.readLine()
          }
          (batch.toList, line != null && (limit == 0 || imported + batch.size < limit))
        }.flatMap { case (batch, hasMore) =>
          if batch.isEmpty then IO.pure(imported)
          else
            persistence.puzzleDao.saveBatch(batch) *>
            IO.println(s"  Imported ${imported + batch.size} puzzles...") *>
            (if hasMore then processBatch(imported + batch.size)
             else IO.pure(imported + batch.size))
        }

      for {
        total <- processBatch(0)
        _     <- IO.blocking(reader.close())
        _     <- IO.println(s"CSV import complete. Total puzzles imported: $total")
      } yield ()
    }

  /**
   * Parse a single CSV line into a [[Puzzle]].
   *
   * Format: PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags
   */
  private def parseCsvLine(line: String): Option[Puzzle] =
    try {
      val parts = line.split(",", -1)
      if parts.length < 8 then None
      else
        val id       = parts(0).trim
        val fen      = parts(1).trim
        val moves    = parts(2).trim.split(" ").toList
        val rating   = parts(3).trim.toInt
        val themes   = parts(7).trim.split(" ").filter(_.nonEmpty).toList
        Some(Puzzle(id, fen, moves, rating, themes))
    } catch {
      case _: Exception => None
    }

  /**
   * Seed the puzzle themes table from the hardcoded Lichess theme list.
   */
  def seedThemes(): IO[Unit] =
    val themes = PuzzleSeeder.THEMES
    for {
      _ <- IO.println(s"Seeding ${themes.size} puzzle themes...")
      _ <- themes.traverse(persistence.puzzleDao.saveTheme)
      _ <- IO.println("Theme seeding complete.")
    } yield ()


object PuzzleSeeder:

  /**
   * All puzzle themes from the Lichess database, extracted from puzzleTheme.xml.
   */
  val THEMES: List[PuzzleTheme] = List(
    PuzzleTheme("advancedPawn", "Advanced pawn", "One of your pawns is deep into the opponent position, maybe threatening to promote."),
    PuzzleTheme("advantage", "Advantage", "Seize your chance to get a decisive advantage. (200cp ≤ eval ≤ 600cp)"),
    PuzzleTheme("anastasiaMate", "Anastasia's mate", "A knight and rook or queen team up to trap the opposing king between the side of the board and a friendly piece."),
    PuzzleTheme("arabianMate", "Arabian mate", "A knight and a rook team up to trap the opposing king on a corner of the board."),
    PuzzleTheme("attackingF2F7", "Attacking f2 or f7", "An attack focusing on the f2 or f7 pawn, such as in the fried liver opening."),
    PuzzleTheme("attraction", "Attraction", "An exchange or sacrifice encouraging or forcing an opponent piece to a square that allows a follow-up tactic."),
    PuzzleTheme("backRankMate", "Back rank mate", "Checkmate the king on the home rank, when it is trapped there by its own pieces."),
    PuzzleTheme("bishopEndgame", "Bishop endgame", "An endgame with only bishops and pawns."),
    PuzzleTheme("bodenMate", "Boden's mate", "Two attacking bishops on criss-crossing diagonals deliver mate to a king obstructed by friendly pieces."),
    PuzzleTheme("capturingDefender", "Capture the defender", "Removing a piece that is critical to defence of another piece."),
    PuzzleTheme("castling", "Castling", "Bring the king to safety, and deploy the rook for attack."),
    PuzzleTheme("clearance", "Clearance", "A move, often with tempo, that clears a square, file or diagonal for a follow-up tactical idea."),
    PuzzleTheme("crushing", "Crushing", "Spot the opponent blunder to obtain a crushing advantage. (eval ≥ 600cp)"),
    PuzzleTheme("defensiveMove", "Defensive move", "A precise move or sequence of moves that is needed to avoid losing material or another advantage."),
    PuzzleTheme("deflection", "Deflection", "A move that distracts an opposing piece from another duty that it performs."),
    PuzzleTheme("discoveredAttack", "Discovered attack", "Moving a piece that previously blocked an attack by a long range piece, out of the way."),
    PuzzleTheme("discoveredCheck", "Discovered check", "Move a piece to reveal a check from a hidden attacking piece."),
    PuzzleTheme("doubleCheck", "Double check", "Checking with two pieces at once, as a result of a discovered attack."),
    PuzzleTheme("doubleBishopMate", "Double bishop mate", "Two attacking bishops on adjacent diagonals deliver mate."),
    PuzzleTheme("dovetailMate", "Dovetail mate", "A queen delivers mate to an adjacent king, whose only two escape squares are obstructed by friendly pieces."),
    PuzzleTheme("endgame", "Endgame", "A tactic during the last phase of the game."),
    PuzzleTheme("equality", "Equality", "Come back from a losing position, and secure a draw or a balanced position. (eval ≤ 200cp)"),
    PuzzleTheme("exposedKing", "Exposed king", "A tactic involving a king with few defenders around it, often leading to checkmate."),
    PuzzleTheme("fork", "Fork", "A move where the moved piece attacks two opponent pieces at once."),
    PuzzleTheme("hangingPiece", "Hanging piece", "A tactic involving an opponent piece being undefended or insufficiently defended and free to capture."),
    PuzzleTheme("hookMate", "Hook mate", "Checkmate with a rook, knight, and pawn along with one enemy pawn to limit the enemy king's escape."),
    PuzzleTheme("interference", "Interference", "Moving a piece between two opponent pieces to leave one or both undefended."),
    PuzzleTheme("intermezzo", "Intermezzo", "Instead of playing the expected move, first interpose another move posing an immediate threat. Also known as Zwischenzug."),
    PuzzleTheme("kingsideAttack", "Kingside attack", "An attack of the opponent's king, after they castled on the king side."),
    PuzzleTheme("knightEndgame", "Knight endgame", "An endgame with only knights and pawns."),
    PuzzleTheme("long", "Long puzzle", "Three moves to win."),
    PuzzleTheme("master", "Master games", "Puzzles from games played by titled players."),
    PuzzleTheme("masterVsMaster", "Master vs Master games", "Puzzles from games between two titled players."),
    PuzzleTheme("mate", "Checkmate", "Win the game with style."),
    PuzzleTheme("mateIn1", "Mate in 1", "Deliver checkmate in one move."),
    PuzzleTheme("mateIn2", "Mate in 2", "Deliver checkmate in two moves."),
    PuzzleTheme("mateIn3", "Mate in 3", "Deliver checkmate in three moves."),
    PuzzleTheme("mateIn4", "Mate in 4", "Deliver checkmate in four moves."),
    PuzzleTheme("mateIn5", "Mate in 5 or more", "Figure out a long mating sequence."),
    PuzzleTheme("middlegame", "Middlegame", "A tactic during the second phase of the game."),
    PuzzleTheme("oneMove", "One-move puzzle", "A puzzle that is only one move long."),
    PuzzleTheme("opening", "Opening", "A tactic during the first phase of the game."),
    PuzzleTheme("pawnEndgame", "Pawn endgame", "An endgame with only pawns."),
    PuzzleTheme("pin", "Pin", "A tactic involving pins, where a piece is unable to move without revealing an attack on a higher value piece."),
    PuzzleTheme("promotion", "Promotion", "Promote one of your pawn to a queen or minor piece."),
    PuzzleTheme("queenEndgame", "Queen endgame", "An endgame with only queens and pawns."),
    PuzzleTheme("queenRookEndgame", "Queen and Rook", "An endgame with only queens, rooks and pawns."),
    PuzzleTheme("queensideAttack", "Queenside attack", "An attack of the opponent's king, after they castled on the queen side."),
    PuzzleTheme("quietMove", "Quiet move", "A move that does not check, capture, or create an immediate threat, but prepares a hidden and unavoidable threat."),
    PuzzleTheme("rookEndgame", "Rook endgame", "An endgame with only rooks and pawns."),
    PuzzleTheme("sacrifice", "Sacrifice", "A tactic involving giving up material in the short-term, to gain an advantage again after a forced sequence of moves."),
    PuzzleTheme("short", "Short puzzle", "Two moves to win."),
    PuzzleTheme("skewer", "Skewer", "A motif involving a high value piece being attacked, moving out the way, and allowing a lower value piece behind it to be captured."),
    PuzzleTheme("smotheredMate", "Smothered mate", "A checkmate delivered by a knight in which the mated king is unable to move because it is surrounded by its own pieces."),
    PuzzleTheme("superGM", "Super GM games", "Puzzles from games played by the best players in the world."),
    PuzzleTheme("trappedPiece", "Trapped piece", "A piece is unable to escape capture as it has limited moves."),
    PuzzleTheme("underPromotion", "Underpromotion", "Promotion to a knight, bishop, or rook."),
    PuzzleTheme("veryLong", "Very long puzzle", "Four moves or more to win."),
    PuzzleTheme("xRayAttack", "X-Ray attack", "A piece attacks or defends a square, through an enemy piece."),
    PuzzleTheme("zugzwang", "Zugzwang", "The opponent is limited in the moves they can make, and all moves worsen their position."),
  )
