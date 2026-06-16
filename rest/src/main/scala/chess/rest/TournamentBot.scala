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

    val myBotIdOpt = getBotIdFromToken(token)

    var line = reader.readLine()
    while line != null do
      if line.trim.nonEmpty then
        parse(line) match
          case Right(json) => handleTournamentEvent(tournamentId, token, myBotIdOpt, json)
          case Left(err)   => println(s"Failed to parse tournament event: $line")
      line = reader.readLine()

  private def getBotIdFromToken(token: String): Option[String] =
    try {
      val parts = token.split("\\.")
      if parts.length >= 2 then
        val decodedBytes = java.util.Base64.getUrlDecoder.decode(parts(1))
        val payload = new String(decodedBytes, "UTF-8")
        parse(payload).toOption.flatMap(_.hcursor.get[String]("sub").toOption)
      else None
    } catch {
      case _: Exception => None
    }

  private def handleTournamentEvent(tournamentId: String, token: String, myBotIdOpt: Option[String], json: Json): Unit =
    val cursor = json.hcursor
    cursor.get[String]("type").toOption match
      case Some("gameStart") =>
        val gameId = cursor.get[String]("gameId").getOrElse("")
        myBotIdOpt match {
          case Some(myBotId) =>
            new Thread(() => {
              val detailsReq = HttpRequest.newBuilder()
                .uri(URI.create(s"$baseUrl/$tournamentId/game/$gameId"))
                .header("Authorization", s"Bearer $token")
                .GET()
                .build()
              try {
                val detailsRes = client.send(detailsReq, HttpResponse.BodyHandlers.ofString())
                if detailsRes.statusCode() == 200 then
                  parse(detailsRes.body()) match
                    case Right(gameJson) =>
                      val gameCursor = gameJson.hcursor
                      val whiteId = gameCursor.downField("white").get[String]("id").getOrElse("")
                      val blackId = gameCursor.downField("black").get[String]("id").getOrElse("")
                      
                      if whiteId == myBotId then
                        println(s"[$gameId] Involviert! Ich spiele WEISS ($myBotId)")
                        handleGameStream(tournamentId, gameId, token, "white")
                      else if blackId == myBotId then
                        println(s"[$gameId] Involviert! Ich spiele SCHWARZ ($myBotId)")
                        handleGameStream(tournamentId, gameId, token, "black")
                      else
                        // Ignore games we are not playing in
                        ()
                    case Left(_) => ()
              } catch {
                case e: Exception => println(s"[$gameId] Failed to fetch game details on start: ${e.getMessage}")
              }
            }).start()
          case None =>
            println("Warnung: Bot-ID konnte nicht ermittelt werden.")
        }
        
      case Some("tournamentFinished") =>
        println("Tournament finished!")
        sys.exit(0)
        
      case Some(other) =>
        println(s"Tournament Event: $other")
        
      case None =>

  private def handleGameStream(tournamentId: String, gameId: String, token: String, myColor: String): Unit =
    // Bootstrap: Fetch current game state to see if it is already our turn
    val stateReq = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/$tournamentId/game/$gameId"))
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()
      
    try {
      val stateRes = client.send(stateReq, HttpResponse.BodyHandlers.ofString())
      if stateRes.statusCode() == 200 then
        parse(stateRes.body()) match
          case Right(json) =>
            val cursor = json.hcursor
            val turn = cursor.get[String]("turn").getOrElse("")
            val fen = cursor.get[String]("fen").getOrElse("")
            val status = cursor.get[String]("status").getOrElse("")
            if status == "ongoing" && turn == myColor then
              println(s"[$gameId] Bootstrapping: It's my turn ($myColor)!")
              makeMove(tournamentId, gameId, token, fen)
          case Left(_) => ()
    } catch {
      case e: Exception => println(s"[$gameId] Bootstrap fetch failed: ${e.getMessage}")
    }

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
