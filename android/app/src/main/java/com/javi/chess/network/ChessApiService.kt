package com.javi.chess.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

@JsonClass(generateAdapter = true)
data class CommandRequest(
    @Json(name = "command") val command: String
)

@JsonClass(generateAdapter = true)
data class GameStateResponse(
    @Json(name = "fen") val fen: String,
    @Json(name = "status") val status: String,
    @Json(name = "activeColor") val activeColor: String,
    @Json(name = "highlights") val highlights: List<String>,
    @Json(name = "lastMove") val lastMove: String?
)

interface ChessApiService {
    @GET("api/state")
    suspend fun getGameState(): GameStateResponse

    @POST("api/command")
    suspend fun sendCommand(@Body request: CommandRequest)
}
