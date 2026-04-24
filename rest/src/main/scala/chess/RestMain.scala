package chess

import cats.effect.*
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s.*
import chess.controller.GameController
import chess.rest.Http4sRestApi
import chess.persistence.PersistenceModule
import chess.ai.Evaluator

/**
 * Standalone Entry Point für den REST-Microservice.
 *
 * Startet nur den Http4s-Server — ohne GUI und ohne TUI.
 * Das ist der eigenständige "game-service" im Microservices-Sinne.
 *
 * Starten mit:  sbt "rest/run"
 * oder als JAR: java -jar chess-rest.jar
 *
 * Der Service exponiert:
 *   GET  /api/state              → aktueller Spielzustand als JSON
 *   POST /api/command            → Zug / Befehl senden
 *   GET  /api/legal-moves?square → legale Züge für ein Feld
 */
object RestMain extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    for
      _   <- IO(println("==========================================="))
      _   <- IO(println("  ♟  Chess REST Service  |  Port 8080"))
      _   <- IO(println("==========================================="))
      _   <- IO(Evaluator.loadWeights())
      _   <- IO(println("[AI]   Weights loaded."))
      persistence <- PersistenceModule.build()
      _   <- IO(println(s"[DB]   Persistence initialized (${persistence.gameDao.getClass.getSimpleName})"))
      api <- IO(new Http4sRestApi(persistence))
      _   <- IO(GameController.addObserver(api))
      _   <- IO(println("[REST] Starting Http4s Ember server..."))
      res <- EmberServerBuilder.default[IO]
               .withHost(ipv4"0.0.0.0")
               .withPort(port"8080")
               .withHttpApp(api.app)
               .build
               .use { server =>
                 IO(println(s"[REST] Server online → http://localhost:${server.address.getPort}/api/state")) *>
                 IO.never
               }
               .guarantee(persistence.close())
               .as(ExitCode.Success)
    yield res
