package chess.rest

import cats.effect.*
import chess.persistence.PersistenceModule

/**
 * CLI Entry point to seed the puzzle database.
 *
 * Usage:
 *   sbt "rest/runMain chess.rest.PuzzleSeederMain themes"
 *   sbt "rest/runMain chess.rest.PuzzleSeederMain csv D:\path\to\lichess_db_puzzle.csv 1000"
 *
 * @param args(0) Mode: "themes" or "csv"
 * @param args(1) (csv only) Path to the CSV file.
 * @param args(2) (csv only) Limit — max number of puzzles to import (0 = all).
 */
object PuzzleSeederMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    val mode = args.headOption.getOrElse("themes")

    val program = for {
      _ <- IO.println("===========================================")
      _ <- IO.println("  ♟  Chess Puzzle Seeder")
      _ <- IO.println("===========================================")

      persistence <- PersistenceModule.build()
      seeder       = new PuzzleSeeder(persistence)

      _ <- mode match
        case "themes" =>
          seeder.seedThemes()

        case "csv" =>
          val path  = args.lift(1).getOrElse("lichess_db_puzzle.csv")
          val limit = args.lift(2).flatMap(_.toIntOption).getOrElse(0)
          IO.println(s"Settings: path=$path, limit=${if limit == 0 then "all" else limit.toString}") *>
          seeder.seedThemes() *>
          seeder.seedFromCsv(path, limit)

        case other =>
          IO.println(s"Unknown mode: $other. Use 'themes' or 'csv'.")

      _ <- persistence.close()
      _ <- IO.println("Done.")
    } yield ExitCode.Success

    program.handleErrorWith { err =>
      IO.println(s"FATAL ERROR: ${err.getMessage}") *>
      IO(err.printStackTrace()) *>
      IO.pure(ExitCode.Error)
    }
