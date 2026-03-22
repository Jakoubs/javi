package chess.web

import chess.controller.CoreState
import chess.model.{Color, GameStatus, Move, Piece, PieceType, Pos}

object dto:

  final case class PieceDto(
    pos: String,
    color: String,
    pieceType: String
  )

  final case class MoveDto(
    from: String,
    to: String,
    promotion: Option[String]
  )

  final case class StateDto(
    activeColor: String,
    status: String,
    lastMove: Option[MoveDto],
    drawOffer: Boolean,
    pieces: List[PieceDto]
  )

  final case class MakeMoveRequest(
    from: String,
    to: String,
    promotion: Option[String] = None
  )

  final case class CommandResponse(
    state: StateDto,
    message: Option[String]
  )

  final case class MovesResponse(
    from: String,
    targets: List[String]
  )

  private def colorToString(c: Color): String =
    c match
      case Color.White => "white"
      case Color.Black => "black"

  private def pieceTypeToString(pt: PieceType): String =
    pt match
      case PieceType.King   => "king"
      case PieceType.Queen  => "queen"
      case PieceType.Rook   => "rook"
      case PieceType.Bishop => "bishop"
      case PieceType.Knight => "knight"
      case PieceType.Pawn   => "pawn"

  private def statusToString(s: GameStatus): String =
    s match
      case GameStatus.Playing           => "playing"
      case GameStatus.Check(c)          => s"check:${colorToString(c)}"
      case GameStatus.Checkmate(loser)  => s"checkmate:${colorToString(loser)}"
      case GameStatus.Stalemate         => "stalemate"
      case GameStatus.Draw(reason)      => s"draw:$reason"

  private def posToString(p: Pos): String = p.toAlgebraic

  def posString(p: Pos): String = posToString(p)

  private def moveToDto(m: Move): MoveDto =
    MoveDto(
      from = posToString(m.from),
      to = posToString(m.to),
      promotion = m.promotion.map(pieceTypeToString)
    )

  def fromCore(core: CoreState): StateDto =
    val pieces: List[PieceDto] =
      core.game.board.pieces.toList
        .sortBy { case (pos, _) => (pos.row, pos.col) }
        .map { case (pos, Piece(color, pt)) =>
          PieceDto(
            pos = posToString(pos),
            color = colorToString(color),
            pieceType = pieceTypeToString(pt)
          )
        }

    StateDto(
      activeColor = colorToString(core.game.activeColor),
      status = statusToString(core.status),
      lastMove = core.lastMove.map(moveToDto),
      drawOffer = core.drawOffer,
      pieces = pieces
    )

