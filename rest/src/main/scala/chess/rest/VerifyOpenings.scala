package chess.rest

import cats.effect.*
import chess.persistence.PersistenceModule

object VerifyOpenings extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    for {
      persistence <- PersistenceModule.build()
      count <- persistence.openingDao.count()
      _ <- IO.println(s"Verification: Found $count openings in the database.")
      _ <- if (count > 0) {
             // Fetch a sample
             persistence.openingDao.findByFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").flatMap { moves =>
               IO.println(s"Sample moves for start position: ${moves.map(_.move).mkString(", ")}")
             }
           } else IO.unit
      _ <- persistence.close()
    } yield ExitCode.Success
