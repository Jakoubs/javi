package chess.lichess

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.scaladsl.*
import org.apache.pekko.stream.RestartSettings
import org.apache.pekko.stream.scaladsl.RestartSource
import org.apache.pekko.util.ByteString
import io.circe.parser.*
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import java.nio.file.{Files, Paths}

class LichessClient(token: String)(implicit system: ActorSystem[?]) {
  private val baseUrl = "https://lichess.org/api"
  implicit val ec: ExecutionContext = system.executionContext

  private val authHeader = Authorization(OAuth2BearerToken(token))
  private val userAgent = `User-Agent`(ProductVersion("JaviBot", "1.0"))

  private val restartSettings = RestartSettings(1.second, 30.seconds, 0.2)
  private val eventFrameMaxBytes = 64 * 1024
  private val gameFrameMaxBytes = 512 * 1024

  private def retryableStatus(status: StatusCode): Boolean =
    status == StatusCodes.TooManyRequests || status.intValue() >= 500

  /**
   * Streams events from Lichess (challenges, game starts).
   */
  def streamEvents(): Source[LichessEvent, Any] = {
    val request = HttpRequest(
      uri = s"$baseUrl/stream/event",
      headers = List(authHeader, userAgent)
    )

    val framing = Framing.delimiter(ByteString("\n"), eventFrameMaxBytes, allowTruncation = false)
    RestartSource.onFailuresWithBackoff(restartSettings)(() =>
      Source.futureSource {
        Http().singleRequest(request).map { response =>
          if (response.status == StatusCodes.OK) {
            response.entity.dataBytes
              .via(framing)
              .map(_.utf8String.trim)
              .filter(_.nonEmpty)
              .mapConcat { json =>
                import LichessModels.decodeLichessEvent
                decode[LichessEvent](json) match {
                  case Right(event) => List(event)
                  case Left(err) =>
                    // Nicht als Stream-Fehler behandeln: einzelne Zeilen können kaputt sein.
                    println(s"Error decoding event JSON: $err")
                    Nil
                }
              }
          } else {
            response.discardEntityBytes()
            if (retryableStatus(response.status)) {
              throw new RuntimeException(s"Event stream HTTP ${response.status} (will retry)")
            } else {
              println(s"Failed to connect to event stream: ${response.status}")
              Source.empty
            }
          }
        }
      }
    )
  }

  /**
   * Streams game events for a specific game (state updates).
   */
  def streamGame(gameId: String): Source[LichessGameEvent, Any] = {
    val request = HttpRequest(
      uri = s"$baseUrl/bot/game/stream/$gameId",
      headers = List(authHeader, userAgent)
    )

    val framing = Framing.delimiter(ByteString("\n"), gameFrameMaxBytes, allowTruncation = false)
    RestartSource.onFailuresWithBackoff(restartSettings)(() =>
      Source.futureSource {
        Http().singleRequest(request).map { response =>
          if (response.status == StatusCodes.OK) {
            response.entity.dataBytes
              .via(framing)
              .map(_.utf8String.trim)
              .filter(_.nonEmpty)
              .mapConcat { json =>
                import LichessModels.decodeLichessGameEvent
                decode[LichessGameEvent](json) match {
                  case Right(event) => List(event)
                  case Left(err) =>
                    // Einzelne Zeilen können unparseable sein; wir lassen den Stream laufen.
                    println(s"Error decoding game JSON: $err")
                    Nil
                }
              }
          } else {
            response.discardEntityBytes()
            if (retryableStatus(response.status)) {
              throw new RuntimeException(s"Game stream HTTP ${response.status} for $gameId (will retry)")
            } else {
              println(s"Failed to connect to game stream $gameId: ${response.status}")
              Source.empty
            }
          }
        }
      }
    )
  }

  def acceptChallenge(challengeId: String): Future[Boolean] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = s"$baseUrl/challenge/$challengeId/accept",
      headers = List(authHeader, userAgent)
    )
    Http().singleRequest(request).map(_.status == StatusCodes.OK)
  }

  def declineChallenge(challengeId: String): Future[Boolean] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = s"$baseUrl/challenge/$challengeId/decline",
      headers = List(authHeader, userAgent)
    )
    Http().singleRequest(request).map(_.status == StatusCodes.OK)
  }

  def makeMove(gameId: String, move: String): Future[Boolean] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = s"$baseUrl/bot/game/$gameId/move/$move",
      headers = List(authHeader, userAgent)
    )
    Http().singleRequest(request).map { resp =>
      if (resp.status != StatusCodes.OK) {
        println(s"Move failed: ${resp.status}")
      }
      resp.status == StatusCodes.OK
    }
  }

  def challengeBot(botId: String): Future[Boolean] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = s"$baseUrl/challenge/$botId",
      headers = List(authHeader, userAgent),
      entity = FormData("variant" -> "standard", "clock.limit" -> "180", "clock.increment" -> "2").toEntity
    )
    Http().singleRequest(request).map(resp => resp.status == StatusCodes.Created || resp.status == StatusCodes.OK)
  }

  def getAccountInfo(): Future[Option[LichessUser]] = {
    val request = HttpRequest(
      uri = s"$baseUrl/account",
      headers = List(authHeader, userAgent)
    )
    Http().singleRequest(request).flatMap { resp =>
      if (resp.status == StatusCodes.OK) {
        Unmarshal(resp.entity).to[String].map { json =>
          import LichessModels.decodeLichessUser
          decode[LichessUser](json).toOption
        }
      } else Future.successful(None)
    }
  }

  def getOngoingGameIds(): Future[List[String]] = {
    val request = HttpRequest(
      uri = s"$baseUrl/account/playing",
      headers = List(authHeader, userAgent)
    )
    Http().singleRequest(request).flatMap { resp =>
      if (resp.status == StatusCodes.OK) {
        Unmarshal(resp.entity).to[String].map { json =>
          parse(json).toOption
            .flatMap(_.hcursor.downField("nowPlaying").focus)
            .flatMap(_.asArray)
            .map(_.toList.flatMap(_.hcursor.get[String]("gameId").toOption))
            .getOrElse(Nil)
        }
      } else {
        resp.discardEntityBytes()
        Future.successful(Nil)
      }
    }
  }

  def resignGame(gameId: String): Future[Boolean] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = s"$baseUrl/bot/game/$gameId/resign",
      headers = List(authHeader, userAgent)
    )
    Http().singleRequest(request).map { resp =>
      resp.status == StatusCodes.OK || resp.status == StatusCodes.Accepted
    }
  }
}

object LichessClient {
  def loadToken(): String = {
    val envToken = Option(System.getenv("LICHESS_TOKEN")).map(_.trim).filter(_.nonEmpty)
    envToken.getOrElse {
      val cwd = Paths.get("").toAbsolutePath.normalize()
      val candidates = List(
        cwd.resolve("lichess.token"),
        cwd.resolve("..").resolve("lichess.token").normalize()
      ).distinct

      candidates.find(Files.exists(_)) match
        case Some(path) => new String(Files.readAllBytes(path)).trim
        case None =>
          throw new RuntimeException(
            s"lichess.token file missing! Gesucht in: ${candidates.map(_.toString).mkString(", ")}. " +
            "Lege die Datei dort an oder setze die Umgebungsvariable LICHESS_TOKEN."
          )
    }
  }
}
