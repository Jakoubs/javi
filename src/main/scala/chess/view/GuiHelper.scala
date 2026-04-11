package chess.view

import chess.model.{Color => ChessColor, PieceType, Piece}

case class AdvantageInfo(
  whiteCapturedSymbols: String,
  blackCapturedSymbols: String,
  whiteAdvantage: Int,
  blackAdvantage: Int
)

object GuiHelper:

  def formatTime(ms: Long): String =
    val totalSec = Math.max(0, ms) / 1000
    val mn = totalSec / 60
    val sc = totalSec % 60
    f"$mn%02d:$sc%02d"

  def calculateAdvantages(capW: List[PieceType], capB: List[PieceType]): AdvantageInfo =
    val valW = capW.map(PieceType.pieceValue).sum
    val valB = capB.map(PieceType.pieceValue).sum
    
    val pieceOrder = Map(PieceType.Queen -> 0, PieceType.Rook -> 1, PieceType.Bishop -> 2, PieceType.Knight -> 3, PieceType.Pawn -> 4)
    val sortedW = capW.sortBy(pieceOrder.getOrElse(_, 5))
    val sortedB = capB.sortBy(pieceOrder.getOrElse(_, 5))

    AdvantageInfo(
      whiteCapturedSymbols = sortedW.map(pt => Piece(ChessColor.White, pt).symbol).mkString(" "),
      blackCapturedSymbols = sortedB.map(pt => Piece(ChessColor.Black, pt).symbol).mkString(" "),
      whiteAdvantage = Math.max(0, valB - valW),
      blackAdvantage = Math.max(0, valW - valB)
    )
