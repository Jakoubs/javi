package chess.view

import chess.model.{Color => ChessColor, PieceType, Piece}

object GuiHelper:

  def formatTime(ms: Long): String =
    val totalSec = Math.max(0, ms) / 1000
    val mn = totalSec / 60
    val sc = totalSec % 60
    f"$mn%02d:$sc%02d"

  def pieceValue(pt: PieceType): Int = pt match
    case PieceType.Pawn   => 1
    case PieceType.Knight => 3
    case PieceType.Bishop => 3
    case PieceType.Rook   => 5
    case PieceType.Queen  => 9
    case PieceType.King   => 0

  case class CapturedAdvantage(whiteAdvantage: Int, blackAdvantage: Int, whiteCapturedSymbols: String, blackCapturedSymbols: String)

  def calculateAdvantages(capW: List[PieceType], capB: List[PieceType]): CapturedAdvantage =
    val valW = capW.map(pieceValue).sum
    val valB = capB.map(pieceValue).sum
    
    val symbolsW = capW.map(pt => Piece(ChessColor.White, pt).symbol).mkString(" ")
    val symbolsB = capB.map(pt => Piece(ChessColor.Black, pt).symbol).mkString(" ")
    
    CapturedAdvantage(
      whiteAdvantage = valB - valW,
      blackAdvantage = valW - valB,
      whiteCapturedSymbols = symbolsW,
      blackCapturedSymbols = symbolsB
    )
