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
import io.circe.generic.auto.*
import io.circe.generic.semiauto.*
import org.http4s.circe.*
import cats.data.Kleisli
import org.http4s.server.middleware.ErrorHandling

import chess.controller.{GameController, AppState, Command, MessageType, CommandRequest, GameStateResponse, CommandParser}
import chess.controller.{liveMillis, displayFen, historyFen}
import chess.model.{Pos, MoveGenerator, ClockState, MaterialInfo, Color, materialInfo, capturedPieces}
import chess.persistence.dao.{FriendshipDao, OpeningDao, PuzzleDao, SavedGameDao}
import chess.persistence.model.SavedGame
import chess.util.parser.CoordinateMoveParser
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

case class SessionState(
  appState: AppState = AppState.initial,
  flipped: Boolean = false,
  highlights: Set[Pos] = Set.empty,
  selectedPos: Option[Pos] = None,
  message: Option[String] = None
)

case class ChallengeRequest(friendId: Long)
case class ChallengeResponse(partyCode: String)
case class AddFriendRequest(friendName: String)
case class AcceptFriendRequest(friendId: Long)
case class SaveGameRequest(name: String, fen: String, pgn: String)
case class LoadGameRequest(id: String)

class Http4sRestApi(
  kafkaService: KafkaService,
  authService: AuthService,
  friendshipDao: FriendshipDao,
  openingDao:    OpeningDao,
  puzzleDao:     PuzzleDao,
  savedGameDao:  SavedGameDao
):
  implicit val commandReqDecoder: EntityDecoder[IO, CommandRequest] = jsonOf[IO, CommandRequest]
  implicit val authReqDecoder: EntityDecoder[IO, AuthRequest] = jsonOf[IO, AuthRequest]
  implicit val challengeReqDecoder: EntityDecoder[IO, ChallengeRequest] = jsonOf[IO, ChallengeRequest]
  implicit val addFriendReqDecoder: EntityDecoder[IO, AddFriendRequest] = jsonOf[IO, AddFriendRequest]
  implicit val acceptFriendReqDecoder: EntityDecoder[IO, AcceptFriendRequest] = jsonOf[IO, AcceptFriendRequest]
  implicit val saveGameReqDecoder: EntityDecoder[IO, SaveGameRequest] = jsonOf[IO, SaveGameRequest]
  implicit val loadGameReqDecoder: EntityDecoder[IO, LoadGameRequest] = jsonOf[IO, LoadGameRequest]

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

  private def dispatch(sessionId: String, cmd: Command): IO[AppState] = 
    for {
      sessionData <- IO(getSession(Some(sessionId)))
      (id, session) = sessionData
      newState      <- IO(GameController.handleCommand(session.appState, cmd))
      
      _ <- IO(sessions.put(id, session.copy(appState = newState)))
      
      _ <- (cmd, newState) match {
        case (Command.ApplyMove(move), s) if s.messageType != MessageType.Error =>
          kafkaService.publishMove(id, move, s.game.toFen)
        case _ => IO.unit
      }
      
      activeCol = newState.game.activeColor
      isAiTurn = (activeCol == Color.White && newState.aiWhite) || 
                 (activeCol == Color.Black && newState.aiBlack)
                    
      _ <- if (isAiTurn && newState.status == chess.model.GameStatus.Playing) {
        (IO.sleep(500.millis) >> dispatch(sessionId, Command.AiMove)).start.void
      } else IO.unit
      
    } yield newState

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "ping" => Ok("pong")

    case req @ POST -> Root / "api" / "auth" / "register" =>
      req.as[AuthRequest].flatMap(authService.register).flatMap {
        case Right(resp) => Ok(resp.asJson)
        case Left(err) => BadRequest(err.asJson)
      }

    case req @ POST -> Root / "api" / "auth" / "login" =>
      req.as[AuthRequest].flatMap(authService.login).flatMap {
        case Right(resp) => Ok(resp.asJson)
        case Left(err) => BadRequest(err.asJson)
      }

    case GET -> Root / "api" / "auth" / "verify" :? TokenParam(token) =>
      authService.verify(token).flatMap {
        case Right(msg) => Ok(msg)
        case Left(err) => BadRequest(err)
      }

    case GET -> Root / "api" / "social" / "friends" :? UserIdParam(uid) =>
      friendshipDao.getFriends(uid).flatMap(friends => Ok(friends.asJson))

    case req @ POST -> Root / "api" / "social" / "friends" / "add" :? UserIdParam(uid) =>
      req.as[AddFriendRequest].flatMap { addReq =>
        authService.userDao.findByUsername(addReq.friendName).flatMap {
          case Some(friend) =>
            friendshipDao.addFriend(uid, friend.id) >> Ok(s"Friend added")
          case None =>
            NotFound(s"User not found")
        }
      }

    case GET -> Root / "api" / "social" / "friends" / "requests" :? UserIdParam(uid) =>
      friendshipDao.getPendingRequests(uid).flatMap(requests => Ok(requests.asJson))

    case req @ POST -> Root / "api" / "social" / "friends" / "accept" :? UserIdParam(uid) =>
      req.as[AcceptFriendRequest].flatMap { accReq =>
        friendshipDao.acceptFriend(uid, accReq.friendId) >> Ok(s"Request accepted")
      }

    case req @ POST -> Root / "api" / "social" / "challenge" =>
      req.as[ChallengeRequest].flatMap { _ =>
        val partyCode = java.util.UUID.randomUUID().toString.substring(0, 6).toUpperCase()
        Ok(ChallengeResponse(partyCode).asJson)
      }

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
                IO(sessions.put(sessionId, session.copy(flipped = !session.flipped))) >> Ok("View flipped")
              case Command.SelectSquare(optPos) =>
                val isMoveAttempt = optPos.exists(pos => session.highlights.contains(pos) && session.selectedPos.isDefined)
                if (isMoveAttempt && optPos.isDefined) {
                  dispatch(sessionId, Command.ApplyMove(chess.model.Move(session.selectedPos.get, optPos.get))).flatMap { newState =>
                    IO(sessions.get(sessionId).foreach(s => sessions.put(sessionId, s.copy(selectedPos = None, highlights = Set.empty, message = None)))) >>
                    (if (newState.messageType == MessageType.Error) BadRequest(newState.message.getOrElse("Error")) else Ok("Move executed"))
                  }
                } else {
                  IO(handleSelectSquare(session.appState, session, optPos)).flatMap(updated => IO(sessions.put(sessionId, updated)) >> Ok("Square selected"))
                }
              case Command.AiMove | Command.AiSuggest =>
                handleAiWithOpening(cmd, sessionId, session)
              case _ =>
                dispatch(sessionId, cmd).flatMap { newState =>
                  val cleanup = if (cmd.isInstanceOf[Command.ApplyMove]) IO(sessions.get(sessionId).foreach(s => sessions.put(sessionId, s.copy(selectedPos = None, highlights = Set.empty, message = None)))) else IO.unit
                  cleanup >> (if (newState.messageType == MessageType.Error) BadRequest(newState.message.getOrElse("Error")) else Ok("Executed"))
                }
            }
          case Left(_) =>
            dispatch(sessionId, CommandParser.parse(cleaned, session.appState)).flatMap(ns => if (ns.messageType == MessageType.Error) BadRequest(ns.message.getOrElse("Error")) else Ok("Dispatched"))
        }
      }

    case GET -> Root / "api" / "legal-moves" :? SquareParam(squareStr) :? SessionIdParam(sid) =>
      val (_, session) = getSession(sid)
      Pos.fromAlgebraic(squareStr) match {
        case Some(pos) => Ok(MoveGenerator.legalMovesFrom(session.appState.game, pos).map(_.to.toAlgebraic).toList.asJson)
        case None => BadRequest("Invalid square")
      }

    case GET -> Root / "api" / "puzzles" / "themes" =>
      puzzleDao.findAllThemes().flatMap(themes => Ok(themes.asJson))

    case GET -> Root / "api" / "puzzles" / "random" :? ThemeParam(theme) =>
      puzzleDao.getRandom(theme).flatMap {
        case Some(puzzle) => Ok(puzzle.asJson)
        case None         => NotFound("No puzzles found")
      }

    case GET -> Root / "api" / "puzzles" :? ThemeParam(theme) :? OrderParam(order) :? LimitParam(limit) :? OffsetParam(offset) =>
      theme match
        case Some(t) =>
          val desc = order.contains("desc")
          val lim  = limit.getOrElse(20)
          val off  = offset.getOrElse(0)
          puzzleDao.findByTheme(t, desc, lim, off).flatMap(puzzles => Ok(puzzles.asJson))
        case None =>
          BadRequest("Missing required 'theme' query parameter")

    case GET -> Root / "api" / "puzzles" / "legal-moves" :? FenParam(fen) :? SquareParam(squareStr) =>
      chess.model.GameState.fromFen(fen) match {
        case Right(state) =>
          Pos.fromAlgebraic(squareStr) match {
            case Some(pos) => Ok(MoveGenerator.legalMovesFrom(state, pos).map(_.to.toAlgebraic).toList.asJson)
            case None      => BadRequest("Invalid square")
          }
        case Left(_) => BadRequest("Invalid FEN string")
      }

    // ─── Saved Games ─────────────────────────────────────────────────────────

    case req @ POST -> Root / "api" / "games" / "save" :? UserIdParam(uid) =>
      req.as[SaveGameRequest].flatMap { saveReq =>
        val game = SavedGame(
          id = java.util.UUID.randomUUID().toString,
          name = saveReq.name,
          fen = saveReq.fen,
          pgn = saveReq.pgn,
          userId = uid
        )
        savedGameDao.save(game) >> Ok(s"Game '${saveReq.name}' saved")
      }

    case GET -> Root / "api" / "games" / "saved" :? UserIdParam(uid) =>
      savedGameDao.findByUserId(uid).flatMap(games => Ok(games.asJson))

    case req @ POST -> Root / "api" / "games" / "load" :? SessionIdParam(sid) =>
      req.as[LoadGameRequest].flatMap { loadReq =>
        savedGameDao.findSavedGameById(loadReq.id).flatMap {
          case Some(game) =>
            val (sessionId, _) = getSession(sid)
            dispatch(sessionId, Command.LoadFen(game.fen)).flatMap { newState =>
              val msg = s"Loaded game: ${game.name}"
              IO(sessions.get(sessionId).foreach(s => sessions.put(sessionId, s.copy(message = Some(msg))))) >> Ok(msg)
            }
          case None => NotFound("Saved game not found")
        }
      }

    case req =>
      IO(println(s"[REST DEBUG] Unmatched request: ${req.method} ${req.uri}")) >> NotFound(s"Route not found: ${req.uri}")
  }

  private def handleAiWithOpening(cmd: Command, sessionId: String, session: SessionState): IO[Response[IO]] =
    val app = session.appState
    openingDao.findByFen(app.game.toFen).flatMap {
      case Nil => dispatch(sessionId, cmd).flatMap(ns => if (ns.messageType == MessageType.Error) BadRequest(ns.message.getOrElse("Error")) else Ok("AI moved"))
      case openings =>
        val chosen = openings(scala.util.Random.nextInt(openings.size))
        CoordinateMoveParser.parse(chosen.move, app.game).toOption match {
          case Some(move) =>
            if (cmd == Command.AiMove) {
              dispatch(sessionId, Command.ApplyMove(move)).flatMap { _ =>
                val msg = s"AI played opening: ${chosen.name.getOrElse("Book Move")} (${chosen.move})"
                dispatch(sessionId, Command.SetOpening(chosen.name)).flatMap { _ =>
                  IO(sessions.get(sessionId).foreach(s => sessions.put(sessionId, s.copy(selectedPos = None, highlights = Set.empty, message = Some(msg))))) >> Ok(msg)
                }
              }
            } else {
              val msg = s"Opening suggests: ${chosen.move}"
              IO(sessions.get(sessionId).foreach(s => sessions.put(sessionId, s.copy(selectedPos = Some(move.from), highlights = Set(move.to), message = Some(msg))))) >> Ok(msg)
            }
          case None => dispatch(sessionId, cmd).flatMap(_ => Ok("AI moved (fallback)"))
        }
    }

  private def handleSelectSquare(app: AppState, session: SessionState, optPos: Option[Pos]): SessionState =
    optPos match
      case None => session.copy(selectedPos = None, highlights = Set.empty, message = None)
      case Some(pos) =>
        if (session.highlights.contains(pos) && session.selectedPos.isDefined) then session
        else
          app.game.board.get(pos) match
            case None => session.copy(selectedPos = None, highlights = Set.empty, message = Some(s"No piece on ${pos.toAlgebraic}"))
            case Some(piece) if piece.color != app.game.activeColor => session.copy(message = Some("Not your piece"), highlights = Set.empty, selectedPos = None)
            case Some(_) =>
              val targets = MoveGenerator.legalMovesFrom(app.game, pos).map(_.to).toSet
              if (targets.isEmpty) session.copy(message = Some("No legal moves"), highlights = Set.empty, selectedPos = Some(pos))
              else session.copy(highlights = targets, selectedPos = Some(pos), message = None)

  object SquareParam extends QueryParamDecoderMatcher[String]("square")
  object SessionIdParam extends OptionalQueryParamDecoderMatcher[String]("sessionId")
  object UserIdParam extends QueryParamDecoderMatcher[Long]("userId")
  object TokenParam extends QueryParamDecoderMatcher[String]("token")
  object ThemeParam extends OptionalQueryParamDecoderMatcher[String]("theme")
  object OrderParam extends OptionalQueryParamDecoderMatcher[String]("order")
  object LimitParam extends OptionalQueryParamDecoderMatcher[Int]("limit")
  object OffsetParam extends OptionalQueryParamDecoderMatcher[Int]("offset")
  object FenParam extends QueryParamDecoderMatcher[String]("fen")

  val app: HttpApp[IO] = CORS.policy
    .withAllowOriginAll
    .withAllowMethodsAll
    .withAllowHeadersAll
    .apply(
      ErrorHandling.Recover.total(
        Kleisli { (req: Request[IO]) =>
          routes.orNotFound.run(req).handleErrorWith { e =>
            IO.println(s"[REST ERROR] Unhandled exception: ${e.getMessage}") *>
            IO(e.printStackTrace()) *>
            InternalServerError("Internal Server Error")
          }
        }
      )
    )

