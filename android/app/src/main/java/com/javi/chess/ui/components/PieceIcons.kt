package com.javi.chess.ui.components

import androidx.compose.ui.graphics.Color

data class PieceInfo(val char: String, val color: Color)

object PieceIcons {
    // Premium Minimalist Colors
    private val WhitePieceColor = Color(0xFFF0F0F0)
    private val BlackPieceColor = Color(0xFF303030)

    fun getPiece(type: Char): PieceInfo? {
        return when (type) {
            'P' -> PieceInfo("♙", WhitePieceColor)
            'R' -> PieceInfo("♖", WhitePieceColor)
            'N' -> PieceInfo("♘", WhitePieceColor)
            'B' -> PieceInfo("♗", WhitePieceColor)
            'Q' -> PieceInfo("♕", WhitePieceColor)
            'K' -> PieceInfo("♔", WhitePieceColor)
            'p' -> PieceInfo("♟", BlackPieceColor)
            'r' -> PieceInfo("♜", BlackPieceColor)
            'n' -> PieceInfo("♞", BlackPieceColor)
            'b' -> PieceInfo("♝", BlackPieceColor)
            'q' -> PieceInfo("♛", BlackPieceColor)
            'k' -> PieceInfo("♚", BlackPieceColor)
            else -> null
        }
    }
}
