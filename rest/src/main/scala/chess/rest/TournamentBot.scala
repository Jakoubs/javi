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
  val client = HttpClient.newBuilder()
    .executor(Executors.newFixedThreadPool(10))
    .connectTimeout(java.time.Duration.ofSeconds(10))
    .build()

  val activeTournamentStreams = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  val activeGameStreams = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  def main(args: Array[String]): Unit =
    val botName = sys.env.getOrElse("BOT_NAME", "Bot1")
    val defaultUrl = if sys.env.contains("DOCKER_ENV") then "http://host.docker.internal:8086" else "http://localhost:8086"
    val envUrl = sys.env.getOrElse("TOURNAMENT_URL", defaultUrl)

    if args.length >= 2 then
      val tournamentId = args(0)
      val token = args(1)
      val myBotIdOpt = getBotIdFromToken(token)
      println(s"[$botName] Joining tournament $tournamentId in manual mode...")
      joinTournament(envUrl, tournamentId, token)
      println(s"[$botName] Successfully joined! Listening to tournament stream...")
      listenToTournamentStream(envUrl, tournamentId, myBotIdOpt.getOrElse(""), token)
    else
      println(s"Starting bot $botName in AUTO-DISCOVERY mode, connecting to $envUrl...")
      
      // Perform registration to get ID and token
      var myBotId = ""
      var token = ""
      var registered = false
      
      while !registered do
        try {
          val (id, t) = registerBot(envUrl, botName)
          myBotId = id
          token = t
          registered = true
        } catch {
          case e: Exception =>
            println(s"Server not reachable or registration failed for $botName: ${e.getMessage}. Retrying in 5 seconds...")
            Thread.sleep(5000)
        }
        
      println(s"Bot $botName is ready (ID: $myBotId). Polling for tournaments...")
      
      while true do
        try {
          pollAndJoinTournaments(envUrl, myBotId, token, botName)
        } catch {
          case e: Exception =>
            println(s"[$botName] Error in polling loop:")
            e.printStackTrace()
        }
        Thread.sleep(5000)

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

  def registerBot(envUrl: String, botName: String): (String, String) =
    val authUrl = s"${envUrl.stripSuffix("/")}/api/auth/register"
    val registerBody = s"""{"name":"$botName","isBot":true}"""
    val req = HttpRequest.newBuilder()
      .uri(URI.create(authUrl))
      .header("Content-Type", "application/json")
      .timeout(java.time.Duration.ofSeconds(10))
      .POST(HttpRequest.BodyPublishers.ofString(registerBody))
      .build()
    
    val res = client.send(req, HttpResponse.BodyHandlers.ofString())
    if res.statusCode() == 200 || res.statusCode() == 201 then
      parse(res.body()) match
        case Right(json) =>
          val cursor = json.hcursor
          val id = cursor.get[String]("id").getOrElse("")
          val token = cursor.get[String]("token").getOrElse("")
          (id, token)
        case Left(err) =>
          throw new Exception(s"Failed to parse registration response: ${res.body()}")
    else
      throw new Exception(s"Registration failed with code ${res.statusCode()}: ${res.body()}")

  def pollAndJoinTournaments(envUrl: String, myBotId: String, token: String, botName: String): Unit =
    val listUrl = s"${envUrl.stripSuffix("/")}/api/tournament"
    val req = HttpRequest.newBuilder()
      .uri(URI.create(listUrl))
      .header("Authorization", s"Bearer $token")
      .timeout(java.time.Duration.ofSeconds(10))
      .GET()
      .build()
      
    val res = client.send(req, HttpResponse.BodyHandlers.ofString())
    if res.statusCode() == 200 then
      parse(res.body()) match
        case Right(json) =>
          val cursor = json.hcursor
          val createdList = cursor.downField("created").as[List[Json]].getOrElse(Nil)
          val startedList = cursor.downField("started").as[List[Json]].getOrElse(Nil)
          
          val activeTournaments = createdList ++ startedList
          for t <- activeTournaments do
            val tCursor = t.hcursor
            val tId = tCursor.get[String]("id").getOrElse("")
            val tStatus = tCursor.get[String]("status").getOrElse("")
            val participants = tCursor.downField("participants").as[List[Json]].getOrElse(Nil)
            val standingPlayers = tCursor.downField("standing").downField("players").as[List[Json]].getOrElse(Nil)
            val standingBots = standingPlayers.flatMap { p =>
              p.hcursor.downField("bot").as[Json].toOption
            }
            val allBots = participants ++ standingBots

            val isParticipant = allBots.exists { p =>
              p.hcursor.get[String]("id").contains(myBotId) || p.hcursor.get[String]("name").contains(botName)
            }
            
            if isParticipant then
              joinTournament(envUrl, tId, token)
              
              if tStatus == "started" then
                println(s"[$botName] Checking tournament $tId for active games...")
                try {
                  val currentRound = tCursor.get[Int]("round").getOrElse(1)
                  val roundUrl = s"${envUrl.stripSuffix("/")}/api/tournament/$tId/round/$currentRound"
                  val roundReq = HttpRequest.newBuilder()
                    .uri(URI.create(roundUrl))
                    .header("Authorization", s"Bearer $token")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build()
                  val roundRes = client.send(roundReq, HttpResponse.BodyHandlers.ofString())
                  if roundRes.statusCode() == 200 then
                    parse(roundRes.body()) match
                      case Right(roundJson) =>
                        val pairings = roundJson.hcursor.downField("pairings").as[List[Json]].getOrElse(Nil)
                        for p <- pairings do
                          val pCursor = p.hcursor
                          val whiteName = pCursor.downField("white").get[String]("name").getOrElse("")
                          val blackName = pCursor.downField("black").get[String]("name").getOrElse("")
                          val isMyPairing = whiteName == botName || blackName == botName
                          
                          if isMyPairing && pCursor.downField("aggregateOutcome").as[Json].toOption.isEmpty then
                            val matches = pCursor.downField("matches").as[List[Json]].getOrElse(Nil)
                            for m <- matches do
                              val mCursor = m.hcursor
                              if mCursor.downField("outcome").as[Json].toOption.isEmpty then
                                val gameId = mCursor.get[String]("gameId").getOrElse("")
                                if gameId.nonEmpty && !activeGameStreams.contains(gameId) then
                                  println(s"[$botName] Found active game $gameId in tournament $tId, checking...")
                                  checkAndPlayGame(envUrl, tId, gameId, myBotId, token)
                      case Left(_) => ()
                  else
                    println(s"[$botName] Round check for $tId returned status ${roundRes.statusCode()}")
                } catch {
                  case e: Exception => println(s"Failed fallback check for tournament $tId: ${e.getMessage}")
                }
              
                if !activeTournamentStreams.contains(tId) then
                  activeTournamentStreams.add(tId)
                  println(s"Spawning stream listener for tournament $tId")
                  new Thread(() => {
                    try {
                      listenToTournamentStream(envUrl, tId, myBotId, token)
                    } catch {
                      case e: Exception =>
                        println(s"Tournament stream $tId error: ${e.getMessage}")
                    } finally {
                      activeTournamentStreams.remove(tId)
                      println(s"Tournament stream $tId listener stopped")
                    }
                  }).start()
              
        case Left(err) =>
          println(s"Failed to parse tournament list: ${res.body()}")
    else
      println(s"Failed to fetch tournament list: ${res.statusCode()}")

  def joinTournament(envUrl: String, tournamentId: String, token: String): Unit =
    val joinReq = HttpRequest.newBuilder()
      .uri(URI.create(s"${envUrl.stripSuffix("/")}/api/tournament/$tournamentId/join"))
      .header("Authorization", s"Bearer $token")
      .timeout(java.time.Duration.ofSeconds(10))
      .POST(HttpRequest.BodyPublishers.noBody())
      .build()
      
    val joinRes = client.send(joinReq, HttpResponse.BodyHandlers.ofString())
    if joinRes.statusCode() == 200 then
      println(s"Successfully joined tournament $tournamentId")
    else if joinRes.statusCode() == 409 then
      ()
    else
      println(s"Failed to join tournament $tournamentId: ${joinRes.statusCode()} - ${joinRes.body()}")

  def listenToTournamentStream(envUrl: String, tournamentId: String, myBotId: String, token: String): Unit =
    val streamUrl = s"${envUrl.stripSuffix("/")}/api/tournament/$tournamentId/stream"
    val conn = java.net.URI.create(streamUrl).toURL().openConnection().asInstanceOf[java.net.HttpURLConnection]
    conn.setConnectTimeout(10000)
    conn.setReadTimeout(30000)
    conn.setRequestProperty("Authorization", s"Bearer $token")

    val responseCode = conn.getResponseCode()
    if responseCode == 200 then
      val reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))
      var line = reader.readLine()
      while line != null do
        if line.trim.nonEmpty then
          parse(line) match
            case Right(json) =>
              val cursor = json.hcursor
              cursor.get[String]("type").toOption match
                case Some("gameStart") =>
                  val gameId = cursor.get[String]("gameId").getOrElse("")
                  if !activeGameStreams.contains(gameId) then
                    checkAndPlayGame(envUrl, tournamentId, gameId, myBotId, token)
                    
                case Some("tournamentFinished") =>
                  println(s"Tournament $tournamentId finished!")
                  return
                  
                case _ =>
            case Left(err)   => ()
        line = reader.readLine()
    else
      throw new Exception(s"Failed to connect to stream: $responseCode")

  def checkAndPlayGame(envUrl: String, tournamentId: String, gameId: String, myBotId: String, token: String): Unit =
    new Thread(() => {
      activeGameStreams.add(gameId)
      println(s"checkAndPlayGame started for game $gameId...")
      try {
        val detailsReq = HttpRequest.newBuilder()
          .uri(URI.create(s"${envUrl.stripSuffix("/")}/api/tournament/$tournamentId/game/$gameId"))
          .header("Authorization", s"Bearer $token")
          .timeout(java.time.Duration.ofSeconds(10))
          .GET()
          .build()
          
        val detailsRes = client.send(detailsReq, HttpResponse.BodyHandlers.ofString())
        if detailsRes.statusCode() == 200 then
          parse(detailsRes.body()) match
            case Right(gameJson) =>
              val gameCursor = gameJson.hcursor
              val whiteId = gameCursor.downField("white").get[String]("id").getOrElse("")
              val whiteName = gameCursor.downField("white").get[String]("name").getOrElse("")
              val blackId = gameCursor.downField("black").get[String]("id").getOrElse("")
              val blackName = gameCursor.downField("black").get[String]("name").getOrElse("")
              
              val botName = sys.env.getOrElse("BOT_NAME", "Bot1")
              val isWhite = whiteId == myBotId || whiteName == botName
              val isBlack = blackId == myBotId || blackName == botName
              
              if isWhite then
                println(s"[$gameId] Involviert! Ich spiele WEISS ($myBotId)")
                handleGameStream(envUrl, tournamentId, gameId, token, "white")
              else if isBlack then
                println(s"[$gameId] Involviert! Ich spiele SCHWARZ ($myBotId)")
                handleGameStream(envUrl, tournamentId, gameId, token, "black")
              else
                ()
            case Left(_) => ()
      } catch {
        case e: Exception => println(s"[$gameId] Failed to check game: ${e.getMessage}")
      } finally {
        activeGameStreams.remove(gameId)
      }
    }).start()

  private def handleGameStream(envUrl: String, tournamentId: String, gameId: String, token: String, myColor: String): Unit =
    val stateReq = HttpRequest.newBuilder()
      .uri(URI.create(s"${envUrl.stripSuffix("/")}/api/tournament/$tournamentId/game/$gameId"))
      .header("Authorization", s"Bearer $token")
      .timeout(java.time.Duration.ofSeconds(10))
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
              makeMove(envUrl, tournamentId, gameId, token, fen)
          case Left(_) => ()
    } catch {
      case e: Exception => println(s"[$gameId] Bootstrap fetch failed: ${e.getMessage}")
    }

    val gameStreamUrl = s"${envUrl.stripSuffix("/")}/api/tournament/$tournamentId/game/$gameId/stream"
    println(s"Connecting to game stream $gameId...")
    val conn = java.net.URI.create(gameStreamUrl).toURL().openConnection().asInstanceOf[java.net.HttpURLConnection]
    conn.setConnectTimeout(10000)
    conn.setReadTimeout(30000)
    conn.setRequestProperty("Authorization", s"Bearer $token")

    val responseCode = conn.getResponseCode()
    if responseCode == 200 then
      val reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))
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
                  if turn == myColor then makeMove(envUrl, tournamentId, gameId, token, fen)
                
                case Some("move") =>
                  val fen = cursor.get[String]("fen").getOrElse("")
                  val turn = cursor.get[String]("turn").getOrElse("")
                  if turn == myColor then makeMove(envUrl, tournamentId, gameId, token, fen)
                
                case Some("gameEnd") =>
                  println(s"[$gameId] Game ended.")
                  return
                  
                case _ =>
            case Left(err) =>
        line = reader.readLine()
    else
      throw new Exception(s"Failed to connect to game stream: $responseCode")

  private def makeMove(envUrl: String, tournamentId: String, gameId: String, token: String, fen: String): Unit =
    println(s"[$gameId] It's my turn! Analyzing $fen")
    
    GameState.fromFen(fen) match
      case Right(state) =>
        AlphaBetaAgent.bestMove(state, 2000L) match
          case Some(move) =>
            val uci = move.toInputString
            println(s"[$gameId] Best move found: $uci")
            
            val moveReq = HttpRequest.newBuilder()
              .uri(URI.create(s"${envUrl.stripSuffix("/")}/api/tournament/$tournamentId/game/$gameId/move/$uci"))
              .header("Authorization", s"Bearer $token")
              .timeout(java.time.Duration.ofSeconds(10))
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
