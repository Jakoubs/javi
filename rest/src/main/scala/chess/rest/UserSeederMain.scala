package chess.rest

import cats.effect.*
import chess.persistence.PersistenceModule
import scala.concurrent.ExecutionContext

object UserSeederMain extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    val persistenceIO = PersistenceModule.build()
    
    persistenceIO.use { persistence =>
      val seeder = new UserSeeder(persistence)
      seeder.seed()
    }.as(ExitCode.Success)

  // Extension to use PersistenceModule with Resource-like behavior for safety
  extension (pIO: IO[PersistenceModule])
    def use[A](f: PersistenceModule => IO[A]): IO[A] =
      pIO.bracket(f)(_.close())
