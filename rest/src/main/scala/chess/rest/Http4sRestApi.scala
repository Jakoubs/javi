package chess.rest

import cats.effect.*
import org.http4s.*
import org.http4s.ember.server.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.server.middleware.CORS
import io.circe.*
import io.circe.syntax.*
import io.circe.parser.*
import io.circe.generic.semiauto.*
import org.http4s.circe.*

import chess.controller.{GameController, AppState, Command, MessageType, CommandRequest, GameStateResponse, CommandParser}
import chess.controller.{liveMillis, displayFen, historyFen}
import chess.model.{Pos, MoveGenerator, ClockState, MaterialInfo}
import chess.model.{capturedPieces, materialInfo}
import chess.util.Observer
import java.util.concurrent.atomic.AtomicReference

class Http4sRestApi extends Observer[AppState]:
  implicit val commandReqDecoder: EntityDecoder[IO, CommandRequest] = jsonOf[IO, CommandRequest]
  private val currentState = new AtomicReference[AppState](GameController.appState)
  
  override def update(state: AppState): Unit =
    currentState.set(state)

  private def buildStateResponse(state: AppState): GameStateResponse =
    GameStateResponse(
      fen = state.game.toFen,
      displayFen = state.displayFen,
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
      historyFen = state.historyFen,
      historyMoves = chess.util.Pgn.exportHistorySan(state.game),
      clock = state.clock,
      capturedWhite = state.game.capturedPieces(chess.model.Color.White).map(_.toString),
      capturedBlack = state.game.capturedPieces(chess.model.Color.Black).map(_.toString),
      message = state.message,
      training = state.training,
      trainingProgress = state.trainingProgress,
      running = state.running,
      messageIsError = state.messageType == MessageType.Error,
      materialInfo = state.game.materialInfo,
      whiteLiveMillis = state.liveMillis(chess.model.Color.White),
      blackLiveMillis = state.liveMillis(chess.model.Color.Black),
      activePgnParser = state.activePgnParser,
      activeMoveParser = state.activeMoveParser
    )

  private def normalizeCommandJson(jsonString: String): (String, String) =
    val cleaned = if (jsonString.startsWith("\"") && jsonString.endsWith("\"")) {
      jsonString.substring(1, jsonString.length - 1).replace("\\\"", "\"")
    } else {
      jsonString
    }
    
    val finalJson = if (!cleaned.trim.startsWith("{")) {
       s"""{"command": "$cleaned"}"""
    } else {
       cleaned
    }
    (cleaned, finalJson)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "state" =>
      val state = currentState.get()
      Ok(buildStateResponse(state).asJson)

    case req @ POST -> Root / "api" / "command" =>
      req.as[String].flatMap { jsonString =>
        val (cleaned, finalJson) = normalizeCommandJson(jsonString)

        decode[CommandRequest](finalJson) match {
          case Right(cmdReq) =>
            val app = currentState.get()
            val cmd = CommandParser.parse(cmdReq.command, app)
            val newState = GameController.eval(cmd)

            if (newState.messageType == MessageType.Error) {
              BadRequest(newState.message.getOrElse("Error"))
            } else {
              val status = cmd match {
                case Command.NewGame | Command.StartGame(_) => Created("Command dispatched")
                case _ => Ok("Command dispatched")
              }
              status
            }
          case Left(_) =>
            val app = currentState.get()
            val cmd = CommandParser.parse(cleaned, app)
            val newState = GameController.eval(cmd)

            if (newState.messageType == MessageType.Error) {
              BadRequest(newState.message.getOrElse("Error"))
            } else {
              Ok("Command dispatched via raw string")
            }
        }
      }

    case GET -> Root / "api" / "legal-moves" :? SquareParam(squareStr) =>
      val state = currentState.get()
      Pos.fromAlgebraic(squareStr) match {
        case Some(pos) =>
          val moves = MoveGenerator.legalMovesFrom(state.game, pos).map(_.to.toAlgebraic).toList
          Ok(moves.asJson)
        case None =>
          BadRequest(s"Invalid square: $squareStr")
      }
  }

  object SquareParam extends QueryParamDecoderMatcher[String]("square")

  val app: HttpApp[IO] = CORS.policy.withAllowOriginAll
    .withAllowMethodsIn(Set(Method.GET, Method.POST, Method.OPTIONS))
    .apply(routes)
    .orNotFound
