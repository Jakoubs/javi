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
import chess.model.{Pos, MoveGenerator, ClockState, capturedPieces}
import chess.util.Observer
import java.util.concurrent.atomic.AtomicReference

case class CommandRequest(command: String) derives Decoder, Encoder
case class GameStateResponse(
  fen: String, 
  pgn: String,
  status: String, 
  activeColor: String,
  highlights: List[String],
  selectedPos: Option[String],
  lastMove: Option[String],
  aiWhite: Boolean,
  aiBlack: Boolean,
  flipped: Boolean,
  viewIndex: Int,
  historyFen: List[String],
  clock: Option[ClockState],
  capturedWhite: List[String],
  capturedBlack: List[String],
  message: Option[String],
  training: Boolean,
  trainingProgress: Option[String]
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
        `Access-Control-Allow-Headers`("Content-Type", "Authorization")
      )
    )

  val route =
    corsHeader {
      concat(
        options {
          complete(StatusCodes.OK)
        },
        pathPrefix("api") {
          concat(
            path("state") {
              get {
                val state = currentState.get()
                val response = GameStateResponse(
                  fen = state.game.toFen,
                  pgn = chess.util.Pgn.exportPgn(state.game),
                  status = state.status.toString,
                  activeColor = state.game.activeColor.toString,
                  highlights = state.highlights.map(_.toAlgebraic).toList,
                  selectedPos = state.selectedPos.map(_.toAlgebraic),
                  lastMove = state.lastMove.map(_.toInputString),
                  aiWhite = state.aiWhite,
                  aiBlack = state.aiBlack,
                  flipped = state.flipped,
                  viewIndex = state.viewIndex,
                  historyFen = (state.game.history :+ state.game).map(_.toFen),
                  clock = state.clock,
                  capturedWhite = state.game.capturedPieces(chess.model.Color.White).map(_.toString),
                  capturedBlack = state.game.capturedPieces(chess.model.Color.Black).map(_.toString),
                  message = state.message,
                  training = state.training,
                  trainingProgress = state.trainingProgress
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
                      val app = currentState.get()
                      val cmd = CommandParser.parse(cleanedJson, app)
                      GameController.eval(cmd)
                      complete(StatusCodes.OK -> "Command dispatched via raw string")
                  }
                }
              }
            },
            path("legal-moves") {
              get {
                parameter("square") { squareStr =>
                  val state = currentState.get()
                  Pos.fromAlgebraic(squareStr) match {
                    case Some(pos) =>
                      val moves = MoveGenerator.legalMovesFrom(state.game, pos).map(_.to.toAlgebraic).toList
                      complete(HttpEntity(ContentTypes.`application/json`, moves.asJson.noSpaces))
                    case None =>
                      complete(StatusCodes.BadRequest -> s"Invalid square: $squareStr")
                  }
                }
              }
            }
          )
        },
        complete(StatusCodes.NotFound -> "Resource not found")
      )
    }

  def start(port: Int = 8080): Unit =
    Http().newServerAt("0.0.0.0", port).bind(route).onComplete {
      case Success(binding) =>
        println(s"[REST API] Server online at http://localhost:${binding.localAddress.getPort}/")
      case Failure(exception) =>
        println(s"[REST API] Failed to bind HTTP server: ${exception.getMessage}")
        system.terminate()
    }
