package chess.controller

import io.circe.*
import io.circe.generic.semiauto.*
import chess.model.{ClockState, MaterialInfo}

case class CommandRequest(command: String)
object CommandRequest:
  implicit val codec: Codec[CommandRequest] = deriveCodec[CommandRequest]

case class GameStateResponse(
  fen: String, 
  displayFen: String,
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
  historyMoves: List[String],
  clock: Option[ClockState],
  capturedWhite: List[String],
  capturedBlack: List[String],
  message: Option[String],
  training: Boolean,
  trainingProgress: Option[String],
  running: Boolean,
  messageIsError: Boolean,
  materialInfo: MaterialInfo,
  whiteLiveMillis: Long,
  blackLiveMillis: Long,
  activePgnParser: String,
  activeMoveParser: String
)
object GameStateResponse:
  implicit val codec: Codec[GameStateResponse] = deriveCodec[GameStateResponse]
