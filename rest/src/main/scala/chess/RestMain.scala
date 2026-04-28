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
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP", "localhost:9092")

    val program = for {
      _ <- Resource.eval(IO(println("===========================================")))
      _ <- Resource.eval(IO(println("  ♟  Chess REST Service  |  Port 8080")))
      _ <- Resource.eval(IO(println("===========================================")))
      _ <- Resource.eval(IO(Evaluator.loadWeights()))
      _ <- Resource.eval(IO(println("[AI]   Weights loaded.")))
      
      // Initialize Persistence
      persistence <- Resource.make(chess.persistence.PersistenceModule.build())(_.close())
      _           <- Resource.eval(IO(println("[DB]   Persistence initialized.")))
      
      // Initialize Kafka
      kafka       <- chess.rest.KafkaService.make(kafkaBootstrap)
      _           <- Resource.eval(IO(println(s"[KAFKA] Connected to $kafkaBootstrap")))
      
      // Initialize Email
      emailService <- Resource.eval(chess.rest.EmailService.fromEnv())
      
      authService = new chess.rest.AuthService(persistence.userDao, emailService)
      api         <- Resource.eval(IO(new Http4sRestApi(kafka, authService, persistence.friendshipDao, persistence.openingDao, persistence.puzzleDao)))
      
      _           <- Resource.eval(IO(println("[REST] Starting Http4s Ember server...")))
      
      server <- EmberServerBuilder.default[IO]
                 .withHost(ipv4"0.0.0.0")
                 .withPort(port"8080")
                 .withHttpApp(api.app)
                 .build
    } yield server

    program.use { (server: org.http4s.server.Server) =>
      IO(println(s"[REST] Server online → http://localhost:${server.address.getPort}/api/state")) *>
      IO.never
    }.as(ExitCode.Success)
