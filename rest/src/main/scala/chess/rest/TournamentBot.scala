package chess.rest

import io.circe.parser.*
import io.circe.Json
import chess.ai.AlphaBetaAgent
import chess.model.{GameState, Move}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{Executors, TimeUnit}
import scala.util.Using
import java.io.{BufferedReader, InputStreamReader}
import io.circe.HCursor

object TournamentBot:
  val baseUrl = "https://tournament.maichess.berger-software.com/api/tournament"
  
  val client = HttpClient.newBuilder()
    .executor(Executors.newFixedThreadPool(10))
    .build()

  def main(args: Array[String]): Unit =
    if args.length < 2 then
      println("Usage: TournamentBot <tournamentId> <botJwtToken>")
      sys.exit(1)

    val tournamentId = args(0)
    val token = args(1)

    println(s"Joining tournament $tournamentId...")
    
    // 1. Join tournament
    val joinReq = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/$tournamentId/join"))
      .header("Authorization", s"Bearer $token")
      .POST(HttpRequest.BodyPublishers.noBody())
      .build()
      
    val joinRes = client.send(joinReq, HttpResponse.BodyHandlers.ofString())
    if joinRes.statusCode() != 200 then
      println(s"Failed to join tournament: ${joinRes.statusCode()} - ${joinRes.body()}")
      if joinRes.statusCode() != 409 then // ignore if already joined
        sys.exit(1)
    
    println("Successfully joined! Listening to tournament stream...")

    // 2. Stream tournament events
    val streamReq = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/$tournamentId/stream"))
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    val response = client.send(streamReq, HttpResponse.BodyHandlers.ofInputStream())
    val reader = new BufferedReader(new InputStreamReader(response.body()))

    var line = reader.readLine()
    while line != null do
      if line.trim.nonEmpty then
        parse(line) match
          case Right(json) => handleTournamentEvent(tournamentId, token, json)
          case Left(err)   => println(s"Failed to parse tournament event: $line")
      line = reader.readLine()

  private def handleTournamentEvent(tournamentId: String, token: String, json: Json): Unit =
    val cursor = json.hcursor
    cursor.get[String]("type").toOption match
      case Some("gameStart") =>
        val gameId = cursor.get[String]("gameId").getOrElse("")
        val myColor = cursor.get[String]("color").getOrElse("")
        println(s"Game started! Game ID: $gameId, playing as $myColor")
        
        // Handle game stream in a new thread
        new Thread(() => handleGameStream(tournamentId, gameId, token, myColor)).start()
        
      case Some("tournamentFinished") =>
        println("Tournament finished!")
        sys.exit(0)
        
      case Some(other) =>
        println(s"Tournament Event: $other")
        
      case None =>

  private def handleGameStream(tournamentId: String, gameId: String, token: String, myColor: String): Unit =
    val gameReq = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/$tournamentId/game/$gameId/stream"))
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    println(s"Connecting to game stream $gameId...")
    val response = client.send(gameReq, HttpResponse.BodyHandlers.ofInputStream())
    val reader = new BufferedReader(new InputStreamReader(response.body()))

    var line = reader.readLine()
    while line != null do
      if line.trim.nonEmpty then
        parse(line) match
          case Right(json) =>
            val cursor = json.hcursor
            cursor.get[String]("type").toOption match
              case Some("gameState") =>
                val fen = cursor.get[String]("fen").getOrElse("")
                val turn = cursor.get[String]("turn").getOrElse("")
                println(s"[$gameId] Game state updated. Turn: $turn")
                if turn == myColor then makeMove(tournamentId, gameId, token, fen)
              
              case Some("move") =>
                val fen = cursor.get[String]("fen").getOrElse("")
                val turn = cursor.get[String]("turn").getOrElse("")
                if turn == myColor then makeMove(tournamentId, gameId, token, fen)
              
              case Some("gameEnd") =>
                println(s"[$gameId] Game ended.")
                return // Exit thread
                
              case _ =>
          case Left(err) =>
      line = reader.readLine()

  private def makeMove(tournamentId: String, gameId: String, token: String, fen: String): Unit =
    println(s"[$gameId] It's my turn! Analyzing $fen")
    
    GameState.fromFen(fen) match
      case Right(state) =>
        // AlphaBetaAgent bestMove needs state and timeLimitMs
        // Use 2 seconds per move for now
        AlphaBetaAgent.bestMove(state, 2000L) match
          case Some(move) =>
            val uci = move.toInputString
            println(s"[$gameId] Best move found: $uci")
            
            val moveReq = HttpRequest.newBuilder()
              .uri(URI.create(s"$baseUrl/$tournamentId/game/$gameId/move/$uci"))
              .header("Authorization", s"Bearer $token")
              .POST(HttpRequest.BodyPublishers.noBody())
              .build()
              
            val res = client.send(moveReq, HttpResponse.BodyHandlers.ofString())
            if res.statusCode() != 200 then
              println(s"[$gameId] Failed to submit move $uci: ${res.statusCode()} - ${res.body()}")
            else
              println(s"[$gameId] Successfully submitted move $uci")
              
          case None =>
            println(s"[$gameId] No legal moves found!")
      case Left(err) =>
        println(s"[$gameId] Failed to parse FEN: $err")
