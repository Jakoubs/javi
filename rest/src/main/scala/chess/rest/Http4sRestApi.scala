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
import chess.model.{Pos, MoveGenerator, ClockState, MaterialInfo, Color, materialInfo, capturedPieces}
import scala.collection.concurrent.TrieMap

import scala.concurrent.duration.*

case class SessionState(
  appState: AppState = AppState.initial,
  flipped: Boolean = false,
  highlights: Set[Pos] = Set.empty,
  selectedPos: Option[Pos] = None,
  message: Option[String] = None
)

class Http4sRestApi(kafkaService: KafkaService):
  implicit val commandReqDecoder: EntityDecoder[IO, CommandRequest] = jsonOf[IO, CommandRequest]
  private val sessions = TrieMap.empty[String, SessionState]

  private def getSession(sid: Option[String]): (String, SessionState) =
    val id = sid.getOrElse("default")
    (id, sessions.getOrElseUpdate(id, SessionState()))
  
  private def buildStateResponse(session: SessionState): GameStateResponse =
    val state = session.appState
    GameStateResponse(
      fen = state.game.toFen,
      displayFen = state.displayFen,
      pgn = chess.util.Pgn.exportPgn(state.game),
      status = state.status.toString,
      activeColor = state.game.activeColor.toString,
      highlights = session.highlights.map(_.toAlgebraic).toList,
      selectedPos = session.selectedPos.map(_.toAlgebraic),
      lastMove = state.lastMove.map(_.toInputString),
      aiWhite = state.aiWhite,
      aiBlack = state.aiBlack,
      flipped = session.flipped,
      viewIndex = state.viewIndex,
      historyFen = state.historyFen,
      historyMoves = chess.util.Pgn.exportHistorySan(state.game),
      clock = state.clock,
      capturedWhite = state.game.capturedPieces(Color.White).map(_.toString),
      capturedBlack = state.game.capturedPieces(Color.Black).map(_.toString),
      message = session.message.orElse(state.message),
      training = state.training,
      trainingProgress = state.trainingProgress,
      running = state.running,
      messageIsError = state.messageType == MessageType.Error,
      materialInfo = state.game.materialInfo,
      whiteLiveMillis = state.liveMillis(Color.White),
      blackLiveMillis = state.liveMillis(Color.Black),
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

  private def dispatch(sessionId: String, cmd: Command): IO[AppState] = 
    for {
      sessionData <- IO(getSession(Some(sessionId)))
      (id, session) = sessionData
      newState      <- IO(GameController.handleCommand(session.appState, cmd))
      
      // Update session state
      _ <- IO(sessions.put(id, session.copy(appState = newState)))
      
      // ♟ Kafka Integration: Publish every successful move
      _ <- (cmd, newState) match {
        case (Command.ApplyMove(move), s) if s.messageType != MessageType.Error =>
          kafkaService.publishMove(id, move, s.game.toFen)
        case _ => IO.unit
      }
      
      // Auto AI trigger
      activeCol = newState.game.activeColor
      isAiTurn = (activeCol == Color.White && newState.aiWhite) || 
                 (activeCol == Color.Black && newState.aiBlack)
                   
      _ <- if (isAiTurn && newState.status == chess.model.GameStatus.Playing) {
        (IO.sleep(500.millis) >> dispatch(sessionId, Command.AiMove)).start.void
      } else IO.unit
      
    } yield newState

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "state" :? SessionIdParam(sid) =>
      val (id, session) = getSession(sid)
      val response = buildStateResponse(session)
      if (session.message.isDefined) {
        sessions.put(id, session.copy(message = None))
      }
      Ok(response.asJson)

    case req @ POST -> Root / "api" / "command" :? SessionIdParam(sid) =>
      req.as[String].flatMap { jsonString =>
        val (cleaned, finalJson) = normalizeCommandJson(jsonString)
        val (sessionId, session) = getSession(sid)

        decode[CommandRequest](finalJson) match {
          case Right(cmdReq) =>
            val cmd = CommandParser.parse(cmdReq.command, session.appState)
            
            cmd match {
              case Command.Flip =>
                IO(sessions.put(sessionId, session.copy(flipped = !session.flipped))) >>
                Ok("View flipped")
                
              case Command.SelectSquare(optPos) =>
                val isMoveAttempt = optPos.exists(pos => session.highlights.contains(pos) && session.selectedPos.isDefined)
                
                if (isMoveAttempt && optPos.isDefined) {
                  val from = session.selectedPos.get
                  val to = optPos.get
                  val move = chess.model.Move(from, to)
                  dispatch(sessionId, Command.ApplyMove(move)).flatMap { newState =>
                    IO(sessions.get(sessionId).foreach { s =>
                      sessions.put(sessionId, s.copy(selectedPos = None, highlights = Set.empty, message = None))
                    }) >> (
                      if (newState.messageType == MessageType.Error) BadRequest(newState.message.getOrElse("Error"))
                      else Ok("Move executed")
                    )
                  }
                } else {
                  IO(handleSelectSquare(session.appState, session, optPos)).flatMap { updated =>
                    IO(sessions.put(sessionId, updated)) >>
                    Ok("Square selected")
                  }
                }
                
              case _ =>
                dispatch(sessionId, cmd).flatMap { newState =>
                  val cleanup = if (cmd.isInstanceOf[Command.ApplyMove]) {
                    IO(sessions.get(sessionId).foreach { s =>
                      sessions.put(sessionId, s.copy(selectedPos = None, highlights = Set.empty, message = None))
                    })
                  } else IO.unit
                  
                  cleanup >> (
                    if (newState.messageType == MessageType.Error) BadRequest(newState.message.getOrElse("Error"))
                    else {
                      cmd match {
                        case Command.NewGame | Command.StartGame(_) => Created("Started")
                        case _ => Ok("Executed")
                      }
                    }
                  )
                }
            }
          case Left(_) =>
            val cmd = CommandParser.parse(cleaned, session.appState)
            dispatch(sessionId, cmd).flatMap { newState =>
              if (newState.messageType == MessageType.Error) BadRequest(newState.message.getOrElse("Error"))
              else Ok("Dispatched")
            }
        }
      }

    case GET -> Root / "api" / "legal-moves" :? SquareParam(squareStr) :? SessionIdParam(sid) =>
      val (_, session) = getSession(sid)
      Pos.fromAlgebraic(squareStr) match {
        case Some(pos) =>
          val moves = MoveGenerator.legalMovesFrom(session.appState.game, pos).map(_.to.toAlgebraic).toList
          Ok(moves.asJson)
        case None =>
          BadRequest(s"Invalid square")
      }
  }

  object SquareParam extends QueryParamDecoderMatcher[String]("square")
  object SessionIdParam extends OptionalQueryParamDecoderMatcher[String]("sessionId")

  private def handleSelectSquare(app: AppState, session: SessionState, optPos: Option[Pos]): SessionState =
    optPos match
      case None =>
        session.copy(selectedPos = None, highlights = Set.empty, message = None)
      case Some(pos) =>
        if (session.highlights.contains(pos) && session.selectedPos.isDefined) then
          session
        else
          app.game.board.get(pos) match
            case None =>
              session.copy(selectedPos = None, highlights = Set.empty, message = Some(s"No piece on ${pos.toAlgebraic}"))
            case Some(piece) if piece.color != Color.White && piece.color != Color.Black => // Should not happen with activeColor check
              session.copy(message = Some("That is not your piece."), highlights = Set.empty, selectedPos = None)
            case Some(piece) if piece.color != app.game.activeColor =>
              session.copy(message = Some("That is not your piece."), highlights = Set.empty, selectedPos = None)
            case Some(_) =>
              val targets = MoveGenerator.legalMovesFrom(app.game, pos).map(_.to).toSet
              if targets.isEmpty then
                session.copy(message = Some("No legal moves for that piece."), highlights = Set.empty, selectedPos = Some(pos))
              else
                session.copy(highlights = targets, selectedPos = Some(pos), message = None)

  val app: HttpApp[IO] = CORS.policy.withAllowOriginAll
    .withAllowMethodsIn(Set(Method.GET, Method.POST, Method.OPTIONS))
    .apply(routes)
    .orNotFound
