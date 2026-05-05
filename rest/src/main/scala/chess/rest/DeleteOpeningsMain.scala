package chess.rest

import cats.effect.{ExitCode, IO, IOApp}
import chess.persistence.PersistenceModule

object DeleteOpeningsMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    for
      persistence <- PersistenceModule.build()
      before <- persistence.openingDao.count()
      _ <- IO.println(s"Opening rows before delete: $before")
      _ <- persistence.openingDao.deleteAll()
      after <- persistence.openingDao.count()
      _ <- IO.println(s"Opening rows after delete: $after")
      _ <- persistence.close()
    yield ExitCode.Success

