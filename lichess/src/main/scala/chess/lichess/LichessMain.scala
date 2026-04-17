package chess.lichess

import cats.effect.*
import org.http4s.ember.client.EmberClientBuilder
import scala.concurrent.duration.*

object LichessMain extends IOApp.Simple {

  override def run: IO[Unit] = {
    val program = EmberClientBuilder.default[IO].build.use { httpClient =>
      val token = LichessClient.loadToken()
      val client = new LichessClient(token, httpClient)
      val service = new LichessService(client)

      for {
        _ <- IO(println("Javi Chess Bot - functional Lichess Integration (http4s)"))
        _ <- IO(println("---------------------------------------------------------"))
        
        // Start the service in background
        serviceFiber <- service.run().start
        
        // Handle optional bot challenge if passed in args (demonstration)
        // Note: For real args, we would use IOApp with args
        
        _ <- serviceFiber.join
      } yield ()
    }

    program.handleErrorWith { e =>
      IO(println(s"Kritischer Fehler beim Starten: ${e.getMessage}")) *> IO.unit
    }
  }
}
