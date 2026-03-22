package chess.web

import cats.data.Kleisli
import cats.effect.{IO, IOApp}
import chess.controller.{Command, GameSession}
import chess.model.{MoveGenerator, PieceType, Pos, Move as CoreMove}
import chess.web.dto.{CommandResponse, MakeMoveRequest}
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.*
import org.typelevel.ci.CIStringSyntax

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

object WebMain extends IOApp.Simple:

  private given Encoder[dto.PieceDto] = deriveEncoder
  private given Encoder[dto.MoveDto]  = deriveEncoder
  private given Encoder[dto.StateDto] = deriveEncoder
  private given Encoder[CommandResponse] = deriveEncoder
  private given Encoder[dto.MovesResponse] = deriveEncoder

  private given Decoder[MakeMoveRequest] = deriveDecoder

  private given EntityDecoder[IO, MakeMoveRequest] = jsonOf
  private given EntityEncoder[IO, CommandResponse] = jsonEncoderOf
  private given EntityEncoder[IO, dto.StateDto]    = jsonEncoderOf
  private given EntityEncoder[IO, dto.MovesResponse] = jsonEncoderOf

  def run: IO[Unit] =
    val session = new GameSession()
    runWithSession(session)

  def runWithSession(session: GameSession): IO[Unit] =

    val httpApp = routes(session).orNotFound

    EmberServerBuilder
      .default[IO]
      .withHost(com.comcast.ip4s.Host.fromString("0.0.0.0").get)
      .withPort(com.comcast.ip4s.Port.fromInt(8080).get)
      .withHttpApp(cors(httpApp))
      .build
      .useForever

  private def routes(session: GameSession): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      case GET -> Root / "api" / "state" =>
        Ok(dto.fromCore(session.snapshot()))

      case GET -> Root / "api" / "moves" / from =>
        Pos.fromAlgebraic(from.toLowerCase) match
          case None => BadRequest(dto.MovesResponse(from = from, targets = Nil))
          case Some(pos) =>
            val core = session.snapshot()
            val targets =
              MoveGenerator.legalMovesFrom(core.game, pos).map(m => dto.posString(m.to)).toList.distinct.sorted
            Ok(dto.MovesResponse(from = dto.posString(pos), targets = targets))

      case req @ POST -> Root / "api" / "move" =>
        for
          body <- req.as[MakeMoveRequest]
          response <- applyMove(session, body)
        yield response

      case POST -> Root / "api" / "new" =>
        val msg = session.dispatch(Command.NewGame)
        Ok(CommandResponse(dto.fromCore(session.snapshot()), msg))

      case POST -> Root / "api" / "undo" =>
        val msg = session.dispatch(Command.Undo)
        Ok(CommandResponse(dto.fromCore(session.snapshot()), msg))

      case POST -> Root / "api" / "draw" =>
        val msg = session.dispatch(Command.OfferDraw)
        Ok(CommandResponse(dto.fromCore(session.snapshot()), msg))

      case POST -> Root / "api" / "resign" =>
        val msg = session.dispatch(Command.Resign)
        Ok(CommandResponse(dto.fromCore(session.snapshot()), msg))
    }

  private def applyMove(session: GameSession, req: MakeMoveRequest): IO[Response[IO]] =
    (for
      from <- Pos.fromAlgebraic(req.from.toLowerCase)
      to   <- Pos.fromAlgebraic(req.to.toLowerCase)
      promo <- req.promotion match
        case None => Some(None)
        case Some(p) => promotionFromString(p).map(Some(_))
      move = CoreMove(from, to, promo)
    yield move) match
      case None =>
        BadRequest(CommandResponse(dto.fromCore(session.snapshot()), Some("Invalid move payload.")))
      case Some(mv) =>
        val msg = session.dispatch(Command.MakeMove(mv))
        Ok(CommandResponse(dto.fromCore(session.snapshot()), msg))

  private def promotionFromString(s: String): Option[PieceType] =
    s.trim.toLowerCase match
      case "q" | "queen"  => Some(PieceType.Queen)
      case "r" | "rook"   => Some(PieceType.Rook)
      case "b" | "bishop" => Some(PieceType.Bishop)
      case "n" | "knight" => Some(PieceType.Knight)
      case _              => None

  private def cors(http: HttpApp[IO]): HttpApp[IO] =
    Kleisli { (req: Request[IO]) =>
      if (req.method == Method.OPTIONS) then
        IO.pure(
          Response[IO](Status.NoContent).withHeaders(
            Header.Raw(ci"Access-Control-Allow-Origin", "*"),
            Header.Raw(ci"Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
            Header.Raw(ci"Access-Control-Allow-Headers", "Content-Type, Authorization")
          )
        )
      else
        http(req).map { res =>
          res.withHeaders(
            Header.Raw(ci"Access-Control-Allow-Origin", "*"),
            Header.Raw(ci"Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
            Header.Raw(ci"Access-Control-Allow-Headers", "Content-Type, Authorization")
          )
        }
    }

