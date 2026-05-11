package chess.ai.nn

import chess.model.{Color, GameState, PieceType}

object FeatureEncoder:
  // 12 piece planes + side-to-move + 4 castling + halfmove + fullmove = 18 planes
  val PlaneCount = 18
  val BoardSize = 8
  val TensorSize: Int = PlaneCount * BoardSize * BoardSize

  def encode(state: GameState): Array[Float] =
    val out = Array.fill[Float](TensorSize)(0.0f)

    state.board.pieces.foreach { case (pos, piece) =>
      val plane = piecePlane(piece.color, piece.pieceType)
      out(index(plane, pos.row, pos.col)) = 1.0f
    }

    val stmPlane = 12
    val stm = if state.activeColor == Color.White then 1.0f else 0.0f
    fillPlane(out, stmPlane, stm)

    if state.castlingRights.whiteKingSide then fillPlane(out, 13, 1.0f)
    if state.castlingRights.whiteQueenSide then fillPlane(out, 14, 1.0f)
    if state.castlingRights.blackKingSide then fillPlane(out, 15, 1.0f)
    if state.castlingRights.blackQueenSide then fillPlane(out, 16, 1.0f)

    // Light normalization to keep values in compact range.
    fillPlane(out, 17, math.min(1.0f, state.halfMoveClock.toFloat / 100.0f))
    out

  private def fillPlane(arr: Array[Float], plane: Int, value: Float): Unit =
    val base = plane * BoardSize * BoardSize
    var i = 0
    while i < 64 do
      arr(base + i) = value
      i += 1

  private def piecePlane(color: Color, pieceType: PieceType): Int =
    val offset = if color == Color.White then 0 else 6
    val p = pieceType match
      case PieceType.Pawn => 0
      case PieceType.Knight => 1
      case PieceType.Bishop => 2
      case PieceType.Rook => 3
      case PieceType.Queen => 4
      case PieceType.King => 5
    offset + p

  private def index(plane: Int, row: Int, col: Int): Int =
    plane * 64 + row * 8 + col

