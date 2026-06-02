package chess.rest

import cats.effect.*
import chess.lichess.LichessOpeningExplorer
import chess.persistence.PersistenceModule
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.ExecutionContext

/**
 * CLI Entry point to trigger the opening seeding process.
 * 
 * Usage: sbt "rest/runMain chess.rest.OpeningSeederMain [depth] [branch]"
 * Example: sbt "rest/runMain chess.rest.OpeningSeederMain 6 3"
 */
object OpeningSeederMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    val depth = args.lift(0).flatMap(_.toIntOption).getOrElse(6)
    val branch = args.lift(1).flatMap(_.toIntOption).getOrElse(3)

    // Pekko ActorSystem for Lichess HTTP Client
    val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "SeederSystem")
    given ActorSystem[Nothing] = system
    given ec: ExecutionContext = system.executionContext

    val program = for {
      _           <- IO.println("===========================================")
      _           <- IO.println("  ♟  Chess Opening Seeder (Lichess -> DB)")
      _           <- IO.println("===========================================")
      _           <- IO.println(s"Settings: depth=$depth, branch=$branch")
      
      persistence <- PersistenceModule.build()
      explorer    = new LichessOpeningExplorer()
      seeder      = new OpeningSeeder(explorer, persistence)
      
      _           <- seeder.seed(depth, branch)
      
      _           <- persistence.close()
      _           <- IO(system.terminate())
      _           <- IO.println("Done.")
    } yield ExitCode.Success

    program.handleErrorWith { err =>
      IO.println(s"FATAL ERROR: ${err.getMessage}") *> 
      IO(system.terminate()) *>
      IO.pure(ExitCode.Error)
    }
