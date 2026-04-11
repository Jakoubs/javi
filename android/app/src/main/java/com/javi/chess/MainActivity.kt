package com.javi.chess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.javi.chess.ui.components.ChessBoard
import com.javi.chess.viewmodel.GameViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFBBCB44),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: GameViewModel = viewModel()
                    val state by viewModel.uiState.collectAsState()
                    val error by viewModel.error.collectAsState()

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "JAVI CHESS",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )

                        state?.let { s ->
                            ChessBoard(state = s, onMove = { viewModel.makeMove(it) })

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Status: ${s.status}",
                                color = Color.White,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Turn: ${s.activeColor}",
                                color = if (s.activeColor == "White") Color.White else Color.Gray,
                                fontSize = 16.sp
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(onClick = { viewModel.undo() }) {
                                    Text("Undo")
                                }
                                Button(
                                    onClick = { viewModel.triggerAiMove() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("AI Move", color = Color.Black)
                                }
                                Button(onClick = { viewModel.resetGame() }) {
                                    Text("New")
                                }
                            }
                        } ?: run {
                            CircularProgressIndicator(modifier = Modifier.padding(top = 64.dp))
                        }

                        error?.let { e ->
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = e, color = Color.Red, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
