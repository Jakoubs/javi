package chess.view

import chess.model.{Color => ChessColor, PieceType, Piece}

object GuiHelper:

  def formatTime(ms: Long): String =
    val totalSec = Math.max(0, ms) / 1000
    val mn = totalSec / 60
    val sc = totalSec % 60
    f"$mn%02d:$sc%02d"
