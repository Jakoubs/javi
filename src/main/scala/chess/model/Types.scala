package chess.model

// ─── Colour ──────────────────────────────────────────────────────────────────

enum Color:
  case White, Black

  def opposite: Color = this match
    case White => Black
    case Black => White

// ─── Piece types ─────────────────────────────────────────────────────────────

enum PieceType:
  case King, Queen, Rook, Bishop, Knight, Pawn

// ─── Piece ───────────────────────────────────────────────────────────────────

case class Piece(color: Color, pieceType: PieceType):
  def symbol: String = (color, pieceType) match
    case (Color.White, PieceType.King)   => "♔"
    case (Color.White, PieceType.Queen)  => "♕"
    case (Color.White, PieceType.Rook)   => "♖"
    case (Color.White, PieceType.Bishop) => "♗"
    case (Color.White, PieceType.Knight) => "♘"
    case (Color.White, PieceType.Pawn)   => "♙"
    case (Color.Black, PieceType.King)   => "♚"
    case (Color.Black, PieceType.Queen)  => "♛"
    case (Color.Black, PieceType.Rook)   => "♜"
    case (Color.Black, PieceType.Bishop) => "♝"
    case (Color.Black, PieceType.Knight) => "♞"
    case (Color.Black, PieceType.Pawn)   => "♟"

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

// ─── Position ────────────────────────────────────────────────────────────────

/** Zero-based (col, row) where (0,0) = a1, (7,7) = h8 */
case class Pos(col: Int, row: Int):
  def isValid: Boolean = col >= 0 && col <= 7 && row >= 0 && row <= 7

  def +(dc: Int, dr: Int): Pos = Pos(col + dc, row + dr)

  /** Algebraic notation, e.g. "e4" */
  def toAlgebraic: String = s"${('a' + col).toChar}${row + 1}"

object Pos:
  /** Parse "e4" → Pos(4, 3) */
  def fromAlgebraic(s: String): Option[Pos] =
    if s.length != 2 then None
    else
      val col = s(0) - 'a'
      val row = s(1) - '1'
      val pos = Pos(col, row)
      if pos.isValid then Some(pos) else None

// ─── Move ────────────────────────────────────────────────────────────────────

case class Move(
  from: Pos,
  to: Pos,
  promotion: Option[PieceType] = None   // for pawn promotion
)

// ─── CastlingRights ──────────────────────────────────────────────────────────

case class CastlingRights(
  whiteKingSide:  Boolean = true,
  whiteQueenSide: Boolean = true,
  blackKingSide:  Boolean = true,
  blackQueenSide: Boolean = true
):
  def disableWhite: CastlingRights = copy(whiteKingSide = false, whiteQueenSide = false)
  def disableBlack: CastlingRights = copy(blackKingSide = false, blackQueenSide = false)
  def disableWhiteKingSide:  CastlingRights = copy(whiteKingSide  = false)
  def disableWhiteQueenSide: CastlingRights = copy(whiteQueenSide = false)
  def disableBlackKingSide:  CastlingRights = copy(blackKingSide  = false)
  def disableBlackQueenSide: CastlingRights = copy(blackQueenSide = false)

// ─── GameStatus ──────────────────────────────────────────────────────────────

enum GameStatus:
  case Playing
  case Check(color: Color)
  case Checkmate(loser: Color)
  case Stalemate
  case Draw(reason: String)
