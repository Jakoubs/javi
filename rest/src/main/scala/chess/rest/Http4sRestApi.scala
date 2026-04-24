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

import chess.model.*
import chess.controller.*
import chess.persistence.PersistenceModule
import chess.util.parser.CoordinateMoveParser
import chess.util.Observer
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap

case class SessionState(
  flipped: Boolean = false,
  selectedPos: Option[Pos] = None,
  highlights: Set[Pos] = Set.empty,
  message: Option[String] = None
)

class Http4sRestApi(persistence: PersistenceModule) extends Observer[AppState]:
  implicit val commandReqDecoder: EntityDecoder[IO, CommandRequest] = jsonOf[IO, CommandRequest]
  private val currentState = new AtomicReference[AppState](GameController.appState)
  private val sessions = TrieMap.empty[String, SessionState]

  private def getSession(sid: Option[String]): (String, SessionState) =
    val id = sid.getOrElse("default")
    (id, sessions.getOrElseUpdate(id, SessionState()))
  
  override def update(state: AppState): Unit =
    currentState.set(state)

  private def buildStateResponse(state: AppState, session: SessionState): GameStateResponse =
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
      capturedWhite = state.game.capturedPieces(chess.model.Color.White).map(_.toString),
      capturedBlack = state.game.capturedPieces(chess.model.Color.Black).map(_.toString),
      message = session.message.orElse(state.message),
      training = state.training,
      trainingProgress = state.trainingProgress,
      running = state.running,
      messageIsError = state.messageType == MessageType.Error,
      materialInfo = state.game.materialInfo,
      whiteLiveMillis = state.liveMillis(chess.model.Color.White),
      blackLiveMillis = state.liveMillis(chess.model.Color.Black),
      activePgnParser = state.activePgnParser,
      activeMoveParser = state.activeMoveParser,
      opening = state.opening
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
    case GET -> Root / "api" / "state" :? SessionIdParam(sid) =>
      val state = currentState.get()
      val (_, session) = getSession(sid)
      Ok(buildStateResponse(state, session).asJson)

    case req @ POST -> Root / "api" / "command" :? SessionIdParam(sid) =>
      req.as[String].flatMap { jsonString =>
        val (cleaned, finalJson) = normalizeCommandJson(jsonString)
        val (sessionId, session) = getSession(sid)

        decode[CommandRequest](finalJson) match {
          case Right(cmdReq) =>
            val app = currentState.get()
            val cmd = CommandParser.parse(cmdReq.command, app)
            
            cmd match {
              case Command.Flip =>
                sessions.put(sessionId, session.copy(flipped = !session.flipped))
                Ok("View flipped")
              case Command.SelectSquare(optPos) =>
                val isMoveAttempt = optPos.exists(pos => session.highlights.contains(pos) && session.selectedPos.isDefined)
                
                if (isMoveAttempt && optPos.isDefined) {
                  // Execute the move
                  val from = session.selectedPos.get
                  val to = optPos.get
                  val moveCmd = Command.ApplyMove(chess.model.Move(from, to))
                  val newState = GameController.eval(moveCmd)
                  
                  // Clear session state for next move
                  sessions.put(sessionId, session.copy(selectedPos = None, highlights = Set.empty, message = None))
                  
                  if (newState.messageType == MessageType.Error) {
                    BadRequest(newState.message.getOrElse("Error"))
                  } else {
                    Ok("Move executed")
                  }
                } else {
                  // Normal selection
                  val updated = handleSelectSquare(app, session, optPos)
                  sessions.put(sessionId, updated)
                  Ok("Square selected")
                }
              case Command.AiMove | Command.AiSuggest =>
                handleAiWithOpening(cmd, sessionId, session)
              case _ =>
                val newState = GameController.eval(cmd)
                // Clear highlights on move
                if (cmd.isInstanceOf[Command.ApplyMove]) {
                  sessions.put(sessionId, session.copy(selectedPos = None, highlights = Set.empty, message = None))
                }
                
                if (newState.messageType == MessageType.Error) {
                  BadRequest(newState.message.getOrElse("Error"))
                } else {
                  val status = cmd match {
                    case Command.NewGame | Command.StartGame(_) => Created("Command dispatched")
                    case _ => Ok("Command dispatched")
                  }
                  status
                }
            }
          case Left(_) =>
            // Legacy handling for raw strings
            val app = currentState.get()
            val cmd = CommandParser.parse(cleaned, app)
            val newState = GameController.eval(cmd)
            if (newState.messageType == MessageType.Error) BadRequest(newState.message.getOrElse("Error"))
            else Ok("Command dispatched via raw string")
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

  private def handleAiWithOpening(cmd: Command, sessionId: String, session: SessionState): IO[Response[IO]] =
    val app = currentState.get()
    val fen = app.game.toFen
    
    persistence.openingDao.findByFen(fen).flatMap {
      case Nil =>
        // No opening found, fallback to standard AI
        val newState = GameController.eval(cmd)
        if (newState.messageType == MessageType.Error) BadRequest(newState.message.getOrElse("Error"))
        else Ok("AI moved (search)")
      case openings =>
        // Pick one opening move randomly (or by weight)
        val chosen = openings(scala.util.Random.nextInt(openings.size))
        CoordinateMoveParser.parse(chosen.move, app.game).toOption match {
          case Some(move) =>
            if (cmd == Command.AiMove) {
              val _ = GameController.eval(Command.ApplyMove(move))
              val msg = s"AI played opening: ${chosen.name.getOrElse("Book Move")} (${chosen.move})"
              val _ = GameController.eval(Command.SetOpening(chosen.name))
              val _ = GameController.eval(Command.Unknown(msg))
              sessions.put(sessionId, session.copy(selectedPos = None, highlights = Set.empty, message = Some(msg)))
              Ok(msg)
            } else {
              val msg = s"Opening Book suggests: ${chosen.move} (${chosen.name.getOrElse("Book Move")})"
              val _ = GameController.eval(Command.SetOpening(chosen.name))
              val _ = GameController.eval(Command.Unknown(msg))
              sessions.put(sessionId, session.copy(selectedPos = Some(move.from), highlights = Set(move.to), message = Some(msg)))
              Ok(msg)
            }
          case None =>
            // Fallback if parsing fails
            val newState = GameController.eval(cmd)
            Ok("AI moved (fallback)")
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
          // This is a move attempt - we don't handle moves here, so we return current session state
          // and let the command handler forward the move to GameController.
          session
        else
          app.game.board.get(pos) match
            case None =>
              session.copy(selectedPos = None, highlights = Set.empty, message = Some(s"No piece on ${pos.toAlgebraic}"))
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
