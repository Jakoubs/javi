package chess.lichess

import io.circe.*
import io.circe.generic.semiauto.*

// --- Event Stream Models ---

sealed trait LichessEvent
case class ChallengeEvent(challenge: LichessChallenge) extends LichessEvent
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

case class LichessClock(limit: Int, increment: Int)
case class LichessPlayer(id: Option[String], name: Option[String], title: Option[String], rating: Option[Int])
case class LichessGameState(moves: String, wtime: Long, btime: Long, winc: Long, binc: Long, status: String)

object LichessModels:
  // Custom decoders to handle Lichess "type" field
  implicit val decodeLichessEvent: Decoder[LichessEvent] = (c: HCursor) =>
    c.get[String]("type").flatMap {
      case "challenge"  => c.as[ChallengeEvent]
      case "gameStart"  => c.as[GameStartEvent]
      case "gameFinish" => c.as[GameFinishEvent]
      case other        => Left(DecodingFailure(s"Unknown event type: $other", c.history))
    }

  implicit val decodeLichessGameEvent: Decoder[LichessGameEvent] = (c: HCursor) =>
    c.get[String]("type") match {
      case Right("gameFull")  => c.as[GameFullEvent]
      case Right("gameState") => c.as[GameStateUpdateEvent]
      case Right(other)       => Left(DecodingFailure(s"Unknown game event type: $other", c.history))
      case Left(_)            => c.as[GameStateUpdateEvent] // Sometimes type is missing in partial updates? Actually usually it's there.
    }

  // Automatic derivation for nested classes
  implicit val decodeChallengeEvent: Decoder[ChallengeEvent] = deriveDecoder
  implicit val decodeGameStartEvent: Decoder[GameStartEvent] = deriveDecoder
  implicit val decodeGameFinishEvent: Decoder[GameFinishEvent] = deriveDecoder
  implicit val decodeGameId: Decoder[GameId] = deriveDecoder
  implicit val decodeLichessChallenge: Decoder[LichessChallenge] = deriveDecoder
  implicit val decodeLichessUser: Decoder[LichessUser] = deriveDecoder
  implicit val decodeLichessVariant: Decoder[LichessVariant] = deriveDecoder
  implicit val decodeLichessPerf: Decoder[LichessPerf] = deriveDecoder
  
  implicit val decodeGameFullEvent: Decoder[GameFullEvent] = deriveDecoder
  implicit val decodeGameStateUpdateEvent: Decoder[GameStateUpdateEvent] = deriveDecoder
  implicit val decodeLichessClock: Decoder[LichessClock] = deriveDecoder
  implicit val decodeLichessPlayer: Decoder[LichessPlayer] = deriveDecoder
  implicit val decodeLichessGameState: Decoder[LichessGameState] = deriveDecoder
