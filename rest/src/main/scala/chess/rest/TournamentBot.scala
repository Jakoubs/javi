package chess.rest

import io.circe.parser.*
import io.circe.Json
import chess.ai.AlphaBetaAgent
import chess.model.GameState
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{Executors, atomic}
import java.util.concurrent.atomic.AtomicBoolean
import java.io.{BufferedReader, InputStreamReader}

// Tracks currently running bot sessions so the REST API can report status
case class BotSession(tournamentId: String, startedAt: Long, active: AtomicBoolean)

/** Client for the NowChess Tournament Server (https://github.com/maichess/tournament-server).
 *
 *  Auth:  POST /api/auth/register  →  { id, token }   (JWT, HS256)
 *         Token is a Bearer token on all subsequent requests.
 *
 *  Colors: "white" | "black"  (NOT "w" / "b")
 *
 *  Tournament flow:
 *    POST /api/tournament/{id}/join
 *    GET  /api/tournament/{id}/stream                        (NDJSON)
 *    GET  /api/tournament/{id}/game/{gameId}/stream          (NDJSON)
 *    POST /api/tournament/{id}/game/{gameId}/move/{uci}
 */
object TournamentBot:
  /** Base URL of the NowChess tournament server running at 141.37.74.152:8086. */
  val serverBase = "http://141.37.74.152:8086"
  val apiBase    = s"$serverBase/api"

  val client = HttpClient.newBuilder()
    .executor(Executors.newFixedThreadPool(10))
    .build()

  // Active bot sessions keyed by tournamentId
  private val activeSessions = scala.collection.concurrent.TrieMap.empty[String, BotSession]

  /** Returns all currently active bot sessions. */
  def getActiveSessions: List[BotSession] = activeSessions.values.toList

  /** Register a new bot identity on the tournament server and return its JWT.
   *  Uses POST /api/auth/register with { "name": name, "isBot": true }.
   */
  def registerBot(name: String): Either[String, String] =
    val body = s"""{"name":"$name","isBot":true}"""
    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$apiBase/auth/register"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()

    val res =
      try client.send(req, HttpResponse.BodyHandlers.ofString())
      catch case e: Exception => return Left(s"Registration network error: ${e.getMessage}")

    if res.statusCode() != 201 && res.statusCode() != 200 then
      return Left(s"Registration failed: HTTP ${res.statusCode()} – ${res.body()}")

    parse(res.body()).flatMap(_.hcursor.get[String]("token")).left.map(_.getMessage)

  /** Join a tournament and play autonomously in the background.
   *  Returns Left(errorMessage) if the join request fails immediately,
   *  Right(()) if the bot was launched successfully in the background.
   */
  def joinAndPlay(tournamentId: String, token: String): Either[String, Unit] =
    // 1. Join tournament (blocking – we want to know immediately if it fails)
    val joinReq = HttpRequest.newBuilder()
      .uri(URI.create(s"$apiBase/tournament/$tournamentId/join"))
      .header("Authorization", s"Bearer $token")
      .POST(HttpRequest.BodyPublishers.noBody())
      .build()

    val joinRes =
      try client.send(joinReq, HttpResponse.BodyHandlers.ofString())
      catch case e: Exception => return Left(s"Network error joining tournament: ${e.getMessage}")

    if joinRes.statusCode() != 200 && joinRes.statusCode() != 409 then
      return Left(s"Failed to join tournament: HTTP ${joinRes.statusCode()} – ${joinRes.body()}")

    println(s"[TournamentBot] Joined $tournamentId (${joinRes.statusCode()}). Starting stream…")

    val session = BotSession(tournamentId, System.currentTimeMillis(), new AtomicBoolean(true))
    activeSessions.put(tournamentId, session)

    // 2. Stream tournament events in a daemon thread so it doesn't block the JVM
    val thread = new Thread(() => {
      try runTournamentStream(tournamentId, token, session)
      finally
        session.active.set(false)
        activeSessions.remove(tournamentId)
        println(s"[TournamentBot] Session for $tournamentId ended.")
    }, s"tournament-bot-$tournamentId")
    thread.setDaemon(true)
    thread.start()

    Right(())

  // ── Standalone entry point ───────────────────────────────────────────────────

  def main(args: Array[String]): Unit =
    if args.length < 2 then
      println("Usage: TournamentBot <tournamentId> <botJwtToken>")
      sys.exit(1)

    joinAndPlay(args(0), args(1)) match
      case Left(err) =>
        println(s"Error: $err")
        sys.exit(1)
      case Right(_) =>
        // Keep the main thread alive while the daemon thread runs
        Thread.currentThread().join()

  // ── Internal stream logic ────────────────────────────────────────────────────

  private def runTournamentStream(tournamentId: String, token: String, session: BotSession): Unit =
    val streamReq = HttpRequest.newBuilder()
      .uri(URI.create(s"$apiBase/tournament/$tournamentId/stream"))
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    val response = client.send(streamReq, HttpResponse.BodyHandlers.ofInputStream())
    val reader = new BufferedReader(new InputStreamReader(response.body()))

    var line = reader.readLine()
    while line != null && session.active.get() do
      if line.trim.nonEmpty then
        parse(line) match
          case Right(json) => handleTournamentEvent(tournamentId, token, json, session)
          case Left(_)     => println(s"[TournamentBot] Failed to parse event: $line")
      line = reader.readLine()

  private def handleTournamentEvent(tournamentId: String, token: String, json: Json, session: BotSession): Unit =
    val cursor = json.hcursor
    cursor.get[String]("type").toOption match
      case Some("gameStart") =>
        val gameId   = cursor.get[String]("gameId").getOrElse("")
        val myColor  = cursor.get[String]("color").getOrElse("")
        println(s"[TournamentBot] Game started! id=$gameId color=$myColor")
        new Thread(() => handleGameStream(tournamentId, gameId, token, myColor), s"game-$gameId").start()

      case Some("tournamentFinished") =>
        println(s"[TournamentBot] Tournament $tournamentId finished!")
        session.active.set(false)

      case Some(other) =>
        println(s"[TournamentBot] Event: $other")

      case None =>

  private def handleGameStream(tournamentId: String, gameId: String, token: String, myColor: String): Unit =
    // myColor is "white" or "black" as returned by the tournament server
    // turn in GameState events is also "white" or "black"
    val gameReq = HttpRequest.newBuilder()
      .uri(URI.create(s"$apiBase/tournament/$tournamentId/game/$gameId/stream"))
      .header("Authorization", s"Bearer $token")
      .GET()
      .build()

    println(s"[TournamentBot] Connecting to game stream $gameId…")
    val response = client.send(gameReq, HttpResponse.BodyHandlers.ofInputStream())
    val reader   = new BufferedReader(new InputStreamReader(response.body()))

    var line = reader.readLine()
    while line != null do
      if line.trim.nonEmpty then
        parse(line) match
          case Right(json) =>
            val cursor = json.hcursor
            cursor.get[String]("type").toOption match
              case Some("gameState") =>
                val fen  = cursor.get[String]("fen").getOrElse("")
                val turn = cursor.get[String]("turn").getOrElse("")
                println(s"[TournamentBot][$gameId] State update. Turn: $turn")
                if turn == myColor then makeMove(tournamentId, gameId, token, fen)

              case Some("move") =>
                val fen  = cursor.get[String]("fen").getOrElse("")
                val turn = cursor.get[String]("turn").getOrElse("")
                if turn == myColor then makeMove(tournamentId, gameId, token, fen)

              case Some("gameEnd") =>
                println(s"[TournamentBot][$gameId] Game ended.")
                return

              case _ =>
          case Left(_) =>
      line = reader.readLine()

  private def makeMove(tournamentId: String, gameId: String, token: String, fen: String): Unit =
    println(s"[TournamentBot][$gameId] My turn! FEN: $fen")

    // The tournament server uses standard FEN; turn in FEN is "w"/"b" but
    // the server's Color enum encodes as "white"/"black" in stream events.
    // We use our own FEN parser which correctly reads "w"/"b" from the FEN string.
    GameState.fromFen(fen) match
      case Right(state) =>
        AlphaBetaAgent.bestMove(state, 2000L) match
          case Some(move) =>
            val uci = move.toInputString
            println(s"[TournamentBot][$gameId] Best move: $uci")

            val moveReq = HttpRequest.newBuilder()
              .uri(URI.create(s"$apiBase/tournament/$tournamentId/game/$gameId/move/$uci"))
              .header("Authorization", s"Bearer $token")
              .POST(HttpRequest.BodyPublishers.noBody())
              .build()

            val res = client.send(moveReq, HttpResponse.BodyHandlers.ofString())
            if res.statusCode() != 200 then
              println(s"[TournamentBot][$gameId] Move $uci rejected: ${res.statusCode()} – ${res.body()}")
            else
              println(s"[TournamentBot][$gameId] Move $uci accepted.")

          case None =>
            println(s"[TournamentBot][$gameId] No legal moves!")
      case Left(err) =>
        println(s"[TournamentBot][$gameId] FEN parse error: $err")
