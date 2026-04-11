package chess.model

import io.circe.{Decoder, Encoder}

enum Color derives Decoder, Encoder:
  case White, Black

  def opposite: Color = this match
    case White => Black
    case Black => White

enum PieceType derives Decoder, Encoder:
  case King, Queen, Rook, Bishop, Knight, Pawn

object PieceType:
  def pieceValue(pt: PieceType): Int = pt match
    case PieceType.Pawn   => 1
    case PieceType.Knight => 3
    case PieceType.Bishop => 3
    case PieceType.Rook   => 5
    case PieceType.Queen  => 9
    case PieceType.King   => 0

case class Piece(color: Color, pieceType: PieceType):
  def symbol: String = pieceType match
    case PieceType.King   => "♚"
    case PieceType.Queen  => "♛"
    case PieceType.Rook   => "♜"
    case PieceType.Bishop => "♝"
    case PieceType.Knight => "♞"
    case PieceType.Pawn   => "♟"

  def letter: String = (color, pieceType) match
    case (Color.White, PieceType.King)   => "K"
    case (Color.White, PieceType.Queen)  => "Q"
    case (Color.White, PieceType.Rook)   => "R"
    case (Color.White, PieceType.Bishop) => "B"
    case (Color.White, PieceType.Knight) => "N"
    case (Color.White, PieceType.Pawn)   => "P"
    case (Color.Black, PieceType.King)   => "k"
    case (Color.Black, PieceType.Queen)  => "q"
    case (Color.Black, PieceType.Rook)   => "r"
    case (Color.Black, PieceType.Bishop) => "b"
    case (Color.Black, PieceType.Knight) => "n"
    case (Color.Black, PieceType.Pawn)   => "p"

object Piece:
  def fromFenChar(char: Char): Option[Piece] =
    val color =
      if char.isUpper then Color.White
      else if char.isLower then Color.Black
      else return None

    val pieceType = char.toLower match
      case 'k' => Some(PieceType.King)
      case 'q' => Some(PieceType.Queen)
      case 'r' => Some(PieceType.Rook)
      case 'b' => Some(PieceType.Bishop)
      case 'n' => Some(PieceType.Knight)
      case 'p' => Some(PieceType.Pawn)
      case _   => None

    pieceType.map(Piece(color, _))

/** Zero-based (col, row) where (0,0) = a1, (7,7) = h8 */
case class Pos(col: Int, row: Int):
  def isValid: Boolean = col >= 0 && col <= 7 && row >= 0 && row <= 7

  def +(dc: Int, dr: Int): Pos = Pos(col + dc, row + dr)

  def toAlgebraic: String = s"${('a' + col).toChar}${row + 1}"

object Pos:
  def fromAlgebraic(s: String): Option[Pos] =
    if s.length != 2 then None
    else
      val col = s(0) - 'a'
      val row = s(1) - '1'
      val pos = Pos(col, row)
      if pos.isValid then Some(pos) else None

case class Move(
  from: Pos,
  to: Pos,
  promotion: Option[PieceType] = None
):
  def toInputString: String = 
    val base = s"${from.toAlgebraic}${to.toAlgebraic}"
    promotion match
      case Some(PieceType.Queen)  => base + "q"
      case Some(PieceType.Rook)   => base + "r"
      case Some(PieceType.Bishop) => base + "b"
      case Some(PieceType.Knight) => base + "n"
      case _                      => base

case class CastlingRights(
  whiteKingSide: Boolean = true,
  whiteQueenSide: Boolean = true,
  blackKingSide: Boolean = true,
  blackQueenSide: Boolean = true
):
  def disableWhite: CastlingRights = copy(whiteKingSide = false, whiteQueenSide = false)
  def disableBlack: CastlingRights = copy(blackKingSide = false, blackQueenSide = false)
  def disableWhiteKingSide: CastlingRights = copy(whiteKingSide = false)
  def disableWhiteQueenSide: CastlingRights = copy(whiteQueenSide = false)
  def disableBlackKingSide: CastlingRights = copy(blackKingSide = false)
  def disableBlackQueenSide: CastlingRights = copy(blackQueenSide = false)

  def toFen: String =
    val rights = new StringBuilder
    if whiteKingSide then rights.append("K")
    if whiteQueenSide then rights.append("Q")
    if blackKingSide then rights.append("k")
    if blackQueenSide then rights.append("q")
    if rights.nonEmpty then rights.result() else "-"

object CastlingRights:
  def fromFen(value: String): Option[CastlingRights] =
    if value == "-" then Some(CastlingRights(false, false, false, false))
    else if value.nonEmpty && value.forall("KQkq".contains(_)) && value.distinct.length == value.length then
      Some(
        CastlingRights(
          whiteKingSide = value.contains('K'),
          whiteQueenSide = value.contains('Q'),
          blackKingSide = value.contains('k'),
          blackQueenSide = value.contains('q')
        )
      )
    else None

enum GameStatus:
  case Playing
  case Check(color: Color)
  case Checkmate(loser: Color)
  case Timeout(loser: Color)
  case Stalemate
  case Draw(reason: String)

case class ClockState(
  whiteMillis: Long,
  blackMillis: Long,
  incrementMillis: Long,
  lastTickSysTime: Option[Long] = None,
  isActive: Boolean = true
) derives Decoder, Encoder:
  def activeMillis(color: Color): Long = color match
    case Color.White => whiteMillis
    case Color.Black => blackMillis

case class MaterialInfo(
  whiteCapturedSymbols: List[String],
  blackCapturedSymbols: List[String],
  whiteAdvantage: Int,
  blackAdvantage: Int
) derives Decoder, Encoder

case class PlayerInfo(
  color: String,
  capturedSymbols: List[String],
  advantage: Int,
  clockMillis: Long
) derives Decoder, Encoder

case class TimeControl(name: String, initialMillis: Option[Long], incrementMillis: Long = 0):
  def toCommand: String = initialMillis match
    case Some(init) => s"start $init $incrementMillis"
    case None       => "start none"

object TimeControl:
  val presets: List[TimeControl] = List(
    TimeControl("Unlimited", None),
    TimeControl("1|0 Bullet", Some(1 * 60 * 1000L), 0L),
    TimeControl("3|2 Blitz",  Some(3 * 60 * 1000L), 2 * 1000L),
    TimeControl("10|0 Rapid", Some(10 * 60 * 1000L), 0L)
  )
