package chess.lichess

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import io.circe.parser.*
import scala.concurrent.{ExecutionContext, Future}
import LichessModels.*

/**
 * Client for the Lichess Opening Explorer API.
 */
class LichessOpeningExplorer(implicit system: ActorSystem[?]) {
  private val baseUrl = "https://explorer.lichess.ovh/lichess"
  implicit val ec: ExecutionContext = system.executionContext

  /**
   * Fetch opening statistics and moves for a given FEN.
   *
   * @param fen The position to explore.
   * @return A [[LichessOpeningResponse]] if successful.
   */
  def getOpenings(fen: String): Future[Option[LichessOpeningResponse]] = {
    val request = HttpRequest(
      uri = Uri(baseUrl).withQuery(Uri.Query(
        "fen" -> fen, 
        "topGames" -> "0", 
        "recentGames" -> "0",
        "moves" -> "10"
      ))
    )

    Http().singleRequest(request).flatMap { resp =>
      if (resp.status == StatusCodes.OK) {
        Unmarshal(resp.entity).to[String].map { json =>
          decode[LichessOpeningResponse](json) match {
            case Right(res) => Some(res)
            case Left(err) =>
              println(s"Error decoding Lichess Opening JSON: $err")
              None
          }
        }
      } else {
        // Consumer-side rate limiting or other errors
        resp.entity.discardBytes()
        Future.successful(None)
      }
    }
  }
}
