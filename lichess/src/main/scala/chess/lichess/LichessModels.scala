package chess.lichess

import io.circe.*
import io.circe.generic.semiauto.*

// --- Event Stream Models ---

sealed trait LichessEvent
case class ChallengeEvent(challenge: LichessChallenge) extends LichessEvent
case class ChallengeDeclinedEvent(challenge: LichessChallenge) extends LichessEvent
case class ChallengeCanceledEvent(challenge: LichessChallenge) extends LichessEvent
case class GameStartEvent(game: GameId) extends LichessEvent
case class GameFinishEvent(game: GameId) extends LichessEvent

case class LichessChallenge(
  id: String,
  challenger: Option[LichessUser],
  variant: LichessVariant,
  speed: String,
  perf: LichessPerf
)

case class LichessUser(id: String, username: String, title: Option[String])
case class LichessVariant(key: String, name: String)
case class LichessPerf(name: String)
case class GameId(id: String)

// --- Game Stream Models ---

sealed trait LichessGameEvent
case class GameFullEvent(
  id: String,
  variant: LichessVariant,
  createdAt: Option[Long],
  clock: Option[LichessClock],
  speed: String,
  perf: LichessPerf,
  white: LichessPlayer,
  black: LichessPlayer,
  initialFen: String,
  state: LichessGameState
) extends LichessGameEvent

case class GameStateUpdateEvent(
  moves: String,
  wtime: Long,
  btime: Long,
  winc: Long,
  binc: Long,
  status: String
) extends LichessGameEvent

/** Wird im Game-Stream gesendet, aber von uns ignoriert */
case object ChatLineEvent extends LichessGameEvent
case object OpponentGoneEvent extends LichessGameEvent

// Lichess sendet "initial" (in ms), nicht "limit"
case class LichessClock(initial: Int, increment: Int)
case class LichessPlayer(id: Option[String], name: Option[String], title: Option[String], rating: Option[Int])
case class LichessGameState(moves: String, wtime: Long, btime: Long, winc: Long, binc: Long, status: String)

// --- Opening Explorer Models ---

case class LichessOpeningResponse(
  white: Int,
  draws: Int,
  black: Int,
  moves: List[LichessOpeningMove],
  opening: Option[LichessOpeningInfo]
)

case class LichessOpeningMove(
  uci: String,
  san: String,
  averageRating: Int,
  white: Int,
  draws: Int,
  black: Int
)

case class LichessOpeningInfo(
  eco: String,
  name: String
)

object LichessModels:
  private def unknownTypeFailure(kind: String, tpe: String, c: HCursor): Left[DecodingFailure, Nothing] =
    Left(DecodingFailure(s"Unknown Lichess $kind type: $tpe", c.history))

  private def missingTypeFailure(kind: String, c: HCursor): Left[DecodingFailure, Nothing] =
    Left(DecodingFailure(s"Missing Lichess $kind type field", c.history))

  // Custom decoders to handle Lichess "type" field
  implicit val decodeLichessEvent: Decoder[LichessEvent] = (c: HCursor) =>
    c.get[String]("type").flatMap {
      case "challenge"         => c.as[ChallengeEvent]
      case "challengeDeclined" => c.as[ChallengeDeclinedEvent]
      case "challengeCanceled" => c.as[ChallengeCanceledEvent]
      case "gameStart"         => c.as[GameStartEvent]
      case "gameFinish"        => c.as[GameFinishEvent]
      case other               => unknownTypeFailure("event", other, c)
    }

  implicit val decodeLichessGameEvent: Decoder[LichessGameEvent] = (c: HCursor) =>
    c.get[String]("type") match {
      case Right("gameFull")  => c.as[GameFullEvent]
      case Right("gameState") => c.as[GameStateUpdateEvent]
      case Right("chatLine")  => Right(ChatLineEvent)
      case Right("opponentGone") => Right(OpponentGoneEvent)
      case Right(other)       => unknownTypeFailure("game event", other, c)
      case Left(_) =>
        // Defensive fallback: some clients have observed partial updates without "type".
        // We accept only canonical gameState payload shape.
        val looksLikeGameState =
          c.downField("moves").succeeded &&
            c.downField("wtime").succeeded &&
            c.downField("btime").succeeded &&
            c.downField("winc").succeeded &&
            c.downField("binc").succeeded &&
            c.downField("status").succeeded
        if looksLikeGameState then c.as[GameStateUpdateEvent]
        else missingTypeFailure("game event", c)
    }

  // Automatic derivation for nested classes
  implicit val decodeChallengeEvent: Decoder[ChallengeEvent] = deriveDecoder
  implicit val decodeChallengeDeclinedEvent: Decoder[ChallengeDeclinedEvent] = deriveDecoder
  implicit val decodeChallengeCanceledEvent: Decoder[ChallengeCanceledEvent] = deriveDecoder
  implicit val decodeGameStartEvent: Decoder[GameStartEvent] = deriveDecoder
  implicit val decodeGameFinishEvent: Decoder[GameFinishEvent] = deriveDecoder
  implicit val decodeGameId: Decoder[GameId] = deriveDecoder
  implicit val decodeLichessChallenge: Decoder[LichessChallenge] = deriveDecoder
  implicit val decodeLichessUser: Decoder[LichessUser] = (c: HCursor) =>
    for
      id       <- c.get[String]("id")
      username <- c.get[String]("username").orElse(c.get[String]("name"))
      title    <- c.get[Option[String]]("title")
    yield LichessUser(id, username, title)
  implicit val decodeLichessVariant: Decoder[LichessVariant] = deriveDecoder
  implicit val decodeLichessPerf: Decoder[LichessPerf] = deriveDecoder

  implicit val decodeGameFullEvent: Decoder[GameFullEvent] = deriveDecoder
  implicit val decodeGameStateUpdateEvent: Decoder[GameStateUpdateEvent] = deriveDecoder
  implicit val decodeLichessClock: Decoder[LichessClock] = deriveDecoder
  implicit val decodeLichessPlayer: Decoder[LichessPlayer] = deriveDecoder
  implicit val decodeLichessGameState: Decoder[LichessGameState] = deriveDecoder

  implicit val decodeLichessOpeningResponse: Decoder[LichessOpeningResponse] = deriveDecoder
  implicit val decodeLichessOpeningMove: Decoder[LichessOpeningMove] = deriveDecoder
  implicit val decodeLichessOpeningInfo: Decoder[LichessOpeningInfo] = deriveDecoder
