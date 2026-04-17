package chess.lichess

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.scaladsl.*
import org.apache.pekko.util.ByteString
import io.circe.parser.*
import scala.concurrent.{ExecutionContext, Future}
import java.nio.file.{Files, Paths}
import scala.util.Try

class LichessClient(token: String)(implicit system: ActorSystem[?]) {
  private val baseUrl = "https://lichess.org/api"
  implicit val ec: ExecutionContext = system.executionContext

  private val authHeader = Authorization(OAuth2BearerToken(token))
  private val userAgent = `User-Agent`(ProductVersion("JaviBot", "1.0"))

  /**
   * Streams events from Lichess (challenges, game starts).
   */
  def streamEvents(): Source[LichessEvent, Any] = {
    val request = HttpRequest(
      uri = s"$baseUrl/stream/event",
      headers = List(authHeader, userAgent)
    )

    Source.futureSource {
      Http().singleRequest(request).map { response =>
        if (response.status == StatusCodes.OK) {
          response.entity.dataBytes
            .via(Framing.delimiter(ByteString("\n"), 1024, allowTruncation = true))
            .map(_.utf8String.trim)
            .filter(_.nonEmpty)
            .mapConcat { json =>
              import LichessModels.decodeLichessEvent
              decode[LichessEvent](json) match {
                case Right(event) => List(event)
                case Left(err) =>
                  println(s"Error decoding event JSON: $err\nJSON: $json")
                  Nil
              }
            }
        } else {
          println(s"Failed to connect to event stream: ${response.status}")
          Source.empty
        }
      }
    }
  }

  /**
   * Streams game events for a specific game (state updates).
   */
  def streamGame(gameId: String): Source[LichessGameEvent, Any] = {
    val request = HttpRequest(
      uri = s"$baseUrl/bot/game/stream/$gameId",
      headers = List(authHeader, userAgent)
    )

    Source.futureSource {
      Http().singleRequest(request).map { response =>
        if (response.status == StatusCodes.OK) {
          response.entity.dataBytes
            .via(Framing.delimiter(ByteString("\n"), 4096, allowTruncation = true))
            .map(_.utf8String.trim)
            .filter(_.nonEmpty)
            .mapConcat { json =>
              import LichessModels.decodeLichessGameEvent
              decode[LichessGameEvent](json) match {
                case Right(event) => List(event)
                case Left(err) =>
                  // println(s"Error decoding game JSON: $err\nJSON: $json")
                  Nil
              }
            }
        } else {
          println(s"Failed to connect to game stream $gameId: ${response.status}")
          Source.empty
        }
      }
    }
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
}

object LichessClient {
  def loadToken(): String = {
    val path = Paths.get("lichess.token")
    if (Files.exists(path)) {
      new String(Files.readAllBytes(path)).trim
    } else {
      throw new RuntimeException("lichess.token file missing! Bitte erstelle eine Datei 'lichess.token' mit deinem Bot-Token.")
    }
  }
}
