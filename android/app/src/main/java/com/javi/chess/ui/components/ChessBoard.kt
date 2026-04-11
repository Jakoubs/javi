package com.javi.chess.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javi.chess.network.GameStateResponse

@Composable
fun ChessBoard(
    state: GameStateResponse,
    onMove: (String) -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val boardSize = screenWidth - 32.dp
    val squareSize = boardSize / 8

    var selectedSquare by remember { mutableStateOf<String?>(null) }
    
    // Convert FEN to a grid
    val board = remember(state.fen) { parseFen(state.fen) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .size(boardSize)
            .background(Color(0xFF2E2E2E))
    ) {
        Crossfade(targetState = board, animationSpec = tween(500)) { currentBoard ->
            Column {
                for (rank in 7 downTo 0) {
                    Row {
                        for (file in 0..7) {
                            val algebraic = "${'a' + file}${rank + 1}"
                            val isDark = (rank + file) % 2 == 0
                            val bgColor = when {
                                selectedSquare == algebraic -> Color(0xFFBBCB44) // Selected
                                state.highlights.contains(algebraic) -> Color(0xFFF6F669) // Legal target
                                state.lastMove?.contains(algebraic) == true -> Color(0xFFF5F682).copy(alpha = 0.6f) // Last move
                                isDark -> Color(0xFFB58863)
                                else -> Color(0xFFF0D9B5)
                            }

                            Box(
                                modifier = Modifier
                                    .size(squareSize)
                                    .background(bgColor)
                                    .clickable {
                                        val prevSelected = selectedSquare
                                        if (prevSelected == null) {
                                            selectedSquare = algebraic
                                        } else {
                                            if (prevSelected != algebraic) {
                                                onMove("$prevSelected$algebraic")
                                            }
                                            selectedSquare = null
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val piece = currentBoard[rank][file]
                                if (piece != null) {
                                    val pieceInfo = PieceIcons.getPiece(piece)
                                    if (pieceInfo != null) {
                                        Text(
                                            text = pieceInfo.char,
                                            color = pieceInfo.color,
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                
                                // Legal move dot
                                if (state.highlights.contains(algebraic) && currentBoard[rank][file] == null) {
                                    Box(
                                        modifier = Modifier
                                            .size(squareSize / 3)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.2f))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun parseFen(fen: String): Array<Array<Char?>> {
    val board = Array(8) { arrayOfNulls<Char>(8) }
    val parts = fen.split(" ")
    val position = parts[0]
    val ranks = position.split("/")
    
    for (r in 0..7) {
        var f = 0
        for (char in ranks[r]) {
            if (char.isDigit()) {
                f += char.toString().toInt()
            } else {
                board[7 - r][f] = char
                f++
            }
        }
    }
    return board
}
