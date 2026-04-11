package chess.view

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.*
import akka.http.scaladsl.unmarshalling.Unmarshal
import io.circe.parser.decode
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class JaviClient(baseUrl: String = "http://localhost:8080")(implicit system: ActorSystem[Nothing]):
  implicit val ec: ExecutionContext = system.executionContext

  def fetchState(): Future[Either[String, GameStateResponse]] =
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$baseUrl/api/state"))
      entity <- response.entity.toStrict(2.seconds)
      body = entity.data.utf8String
      result = if (response.status.isSuccess()) {
        decode[GameStateResponse](body).left.map(_.getMessage)
      } else {
        Left(s"Server error: ${response.status}")
      }
    } yield result

  def sendCommand(cmd: String): Future[Either[String, String]] =
    for {
      response <- Http().singleRequest(HttpRequest(
        method = HttpMethods.POST,
        uri = s"$baseUrl/api/command",
        entity = HttpEntity(ContentTypes.`application/json`, cmd)
      ))
      result = if (response.status.isSuccess()) Right("OK") else Left(s"Error: ${response.status}")
    } yield result

object JaviClient:
  def apply(baseUrl: String = "http://localhost:8080") =
    val system = ActorSystem(Behaviors.empty, "javi-client")
    new JaviClient(baseUrl)(system)
