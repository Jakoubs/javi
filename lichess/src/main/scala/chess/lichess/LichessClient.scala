package chess.lichess

import cats.effect.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.headers.*
import org.http4s.circe.*
import org.http4s.implicits.*
import io.circe.parser.*
import io.circe.Decoder
import cats.syntax.all.*
import fs2.Stream
import fs2.text
import java.nio.file.{Files, Paths}
import scala.util.Try

class LichessClient(token: String, client: Client[IO]) {
  private val baseUrl = uri"https://lichess.org/api"
  
  private val authHeader = Authorization(Credentials.Token(AuthScheme.Bearer, token))
  private val userAgent = `User-Agent`(ProductId("JaviBot", Some("1.0")))

  private def authenticated(req: Request[IO]): Request[IO] =
    req.putHeaders(authHeader, userAgent)

  /**
   * Streams events from Lichess (challenges, game starts).
   */
  def streamEvents(): Stream[IO, LichessEvent] = {
    val req = authenticated(Request[IO](Method.GET, baseUrl / "stream" / "event"))
    
    client.stream(req).flatMap { resp =>
      if (resp.status == Status.Ok) {
        resp.body
          .through(text.utf8.decode)
          .through(text.lines)
          .filter(_.nonEmpty)
          .mapFilter { json =>
            import LichessModels.decodeLichessEvent
            decode[LichessEvent](json) match {
              case Right(event) => Some(event)
              case Left(err) =>
                println(s"Error decoding event JSON: $err\nJSON: $json")
                None
            }
          }
      } else {
        Stream.exec(IO(println(s"Failed to connect to event stream: ${resp.status}")))
      }
    }
  }

  /**
   * Streams game events for a specific game (state updates).
   */
  def streamGame(gameId: String): Stream[IO, LichessGameEvent] = {
    val req = authenticated(Request[IO](Method.GET, baseUrl / "bot" / "game" / "stream" / gameId))
    
    client.stream(req).flatMap { resp =>
      if (resp.status == Status.Ok) {
        resp.body
          .through(text.utf8.decode)
          .through(text.lines)
          .filter(_.nonEmpty)
          .mapFilter { json =>
            import LichessModels.decodeLichessGameEvent
            decode[LichessGameEvent](json) match {
              case Right(event) => Some(event)
              case Left(_) => None
            }
          }
      } else {
        Stream.exec(IO(println(s"Failed to connect to game stream $gameId: ${resp.status}")))
      }
    }
  }

  def acceptChallenge(challengeId: String): IO[Boolean] = {
    val req = authenticated(Request[IO](Method.POST, baseUrl / "challenge" / challengeId / "accept"))
    client.status(req).map(_ == Status.Ok)
  }

  def declineChallenge(challengeId: String): IO[Boolean] = {
    val req = authenticated(Request[IO](Method.POST, baseUrl / "challenge" / challengeId / "decline"))
    client.status(req).map(_ == Status.Ok)
  }

  def makeMove(gameId: String, move: String): IO[Boolean] = {
    val req = authenticated(Request[IO](Method.POST, baseUrl / "bot" / "game" / gameId / "move" / move))
    client.run(req).use { resp =>
      if (resp.status != Status.Ok) {
        IO(println(s"Move failed: ${resp.status}")) *> IO.pure(false)
      } else IO.pure(true)
    }
  }

  def challengeBot(botId: String): IO[Boolean] = {
    val req = authenticated(Request[IO](Method.POST, baseUrl / "challenge" / botId))
      .withEntity(UrlForm("variant" -> "standard", "clock.limit" -> "180", "clock.increment" -> "2"))
    
    client.status(req).map(s => s == Status.Created || s == Status.Ok)
  }

  def getAccountInfo(): IO[Option[LichessUser]] = {
    val req = authenticated(Request[IO](Method.GET, baseUrl / "account"))
    client.expectOption[String](req).map {
      case Some(json) =>
        import LichessModels.decodeLichessUser
        decode[LichessUser](json).toOption
      case None => None
    }
  }
}

object LichessClient {
  def loadToken(): String = {
    val path = Paths.get("lichess.token")
    if (Files.exists(path)) {
      new String(Files.readAllBytes(path)).trim
    } else {
      throw new RuntimeException("lichess.token file missing!")
    }
  }
}
