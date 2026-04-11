package com.javi.chess.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.javi.chess.network.ChessApiService
import com.javi.chess.network.CommandRequest
import com.javi.chess.network.GameStateResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class GameViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<GameStateResponse?>(null)
    val uiState: StateFlow<GameStateResponse?> = _uiState

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val apiService: ChessApiService

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://10.0.2.2:8080/") // Hardcoded for Android Emulator
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(client)
            .build()

        apiService = retrofit.create(ChessApiService::class.java)

        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                try {
                    val state = apiService.getGameState()
                    _uiState.value = state
                    _error.value = null
                } catch (e: Exception) {
                    _error.value = "Connection Error: ${e.message}"
                }
                delay(1500) // Poll every 1.5 seconds
            }
        }
    }

    fun makeMove(move: String) {
        sendCommand(move)
    }

    fun triggerAiMove() {
        sendCommand("ai")
    }

    fun undo() {
        sendCommand("undo")
    }

    fun resetGame() {
        sendCommand("new")
    }

    private fun sendCommand(cmd: String) {
        viewModelScope.launch {
            try {
                apiService.sendCommand(CommandRequest(cmd))
            } catch (e: Exception) {
                _error.value = "Command Error: ${e.message}"
            }
        }
    }
}
