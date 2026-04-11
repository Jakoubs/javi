package chess.view

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.server.Directives.*
import io.circe.*
import io.circe.syntax.*
import io.circe.parser.*
import scala.concurrent.ExecutionContextExecutor
import scala.util.{Success, Failure}

import chess.controller.{GameController, AppState, Command}
import chess.util.Observer
import java.util.concurrent.atomic.AtomicReference

case class CommandRequest(command: String) derives Decoder, Encoder
case class GameStateResponse(
  fen: String, 
  status: String, 
  activeColor: String,
  highlights: List[String],
  lastMove: Option[String]
) derives Decoder, Encoder

class RestApi extends Observer[AppState]:
  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "chess-rest-api")
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  private val currentState = new AtomicReference[AppState](GameController.appState)

  override def update(state: AppState): Unit =
    currentState.set(state)

  private def corsHeader = 
    respondWithHeaders(
      List(
        `Access-Control-Allow-Origin`.*,
        `Access-Control-Allow-Methods`(HttpMethods.GET, HttpMethods.POST, HttpMethods.OPTIONS),
        `Access-Control-Allow-Headers`("Content-Type")
      )
    )

  val route =
    corsHeader {
      pathPrefix("api") {
        concat(
          options {
            complete(StatusCodes.OK)
          },
          path("state") {
            get {
              val state = currentState.get()
              val response = GameStateResponse(
                fen = state.game.toFen,
                status = state.status.toString,
                activeColor = state.game.activeColor.toString,
                highlights = state.highlights.map(_.toAlgebraic).toList,
                lastMove = state.lastMove.map(_.toInputString)
              )
              complete(HttpEntity(ContentTypes.`application/json`, response.asJson.noSpaces))
            }
          },
          path("command") {
            post {
              entity(as[String]) { jsonString =>
                val cleanedJson = if (jsonString.startsWith("\"") && jsonString.endsWith("\"")) {
                  jsonString.substring(1, jsonString.length - 1).replace("\\\"", "\"")
                } else {
                  jsonString
                }
                
                // If it's a raw string like "e2e4", wrap it in CommandRequest json
                val finalJson = if (!cleanedJson.trim.startsWith("{")) {
                   s"""{"command": "$cleanedJson"}"""
                } else {
                   cleanedJson
                }

                decode[CommandRequest](finalJson) match {
                  case Right(req) =>
                    val app = currentState.get()
                    val cmd = CommandParser.parse(req.command, app)
                    GameController.eval(cmd)
                    complete(StatusCodes.OK -> "Command dispatched")
                  case Left(error) =>
                    // Try parsing as raw command string if JSON decode fails
                    val app = currentState.get()
                    val cmd = CommandParser.parse(cleanedJson, app)
                    GameController.eval(cmd)
                    complete(StatusCodes.OK -> "Command dispatched via raw string")
                }
              }
            }
          }
        )
      }
    }

  def start(port: Int = 8080): Unit =
    Http().newServerAt("0.0.0.0", port).bind(route).onComplete {
      case Success(binding) =>
        println(s"[REST API] Server online at http://localhost:${binding.localAddress.getPort}/")
      case Failure(exception) =>
        println(s"[REST API] Failed to bind HTTP server: ${exception.getMessage}")
        system.terminate()
    }
