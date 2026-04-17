package chess.lichess

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.*
import chess.model.*
import chess.ai.AlphaBetaAgent
import chess.util.parser.CoordinateMoveParser
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}

class LichessService(client: LichessClient)(implicit system: ActorSystem[?]) {
  implicit val ec: ExecutionContext = system.executionContext
  private var botUser: Option[LichessUser] = None
  
  private val activeGames = scala.collection.concurrent.TrieMap.empty[String, (String, String, Int)]

  def start(): Unit = {
    println("Lichess Service startet... Lade Accountinfo.")
    client.getAccountInfo().onComplete {
      case Success(Some(user)) =>
        botUser = Some(user)
        println(s"Eingeloggt als: ${user.username} (ID: ${user.id})")
        startEventStream()
      case _ =>
        println("Fehler beim Laden der Accountinfo. Ist der Token korrekt?")
    }
  }

  private def startEventStream(): Unit = {
    println("Warte auf Lichess-Events...")
    client.streamEvents()
      .runForeach {
        case ChallengeEvent(challenge) =>
          handleChallenge(challenge)
        case GameStartEvent(game) =>
          handleGameStart(game.id)
        case GameFinishEvent(game) =>
          println(s"Spiel beendet Event: ${game.id}")
          activeGames.remove(game.id)
        case _ => // Ignore others
      }
      .onComplete {
        case Success(_) => println("Event stream closed.")
        case Failure(e) => println(s"Event stream error: ${e.getMessage}")
      }
  }

  private def handleChallenge(challenge: LichessChallenge): Unit = {
    if (challenge.variant.key == "standard") {
      println(s"Herausforderung von ${challenge.challenger.map(_.username).getOrElse("Anonym")} akzeptiert.")
      client.acceptChallenge(challenge.id)
    } else {
      println(s"Herausforderung abgelehnt (Variante: ${challenge.variant.key}).")
      client.declineChallenge(challenge.id)
    }
  }

  private def handleGameStart(gameId: String): Unit = {
    println(s"Spiel-Stream gestartet: $gameId")
    
    client.streamGame(gameId)
      .runForeach {
        case full: GameFullEvent =>
          println(s"GameFull erhalten für $gameId")
          val wId = full.white.id.getOrElse("unknown").toLowerCase
          val bId = full.black.id.getOrElse("unknown").toLowerCase
          println(s"Spieler: Weiß=$wId, Schwarz=$bId")
          activeGames.put(gameId, (wId, bId, -1))
          processGameUpdate(gameId, full.state)
          
        case stateUpdate: GameStateUpdateEvent =>
          // println(s"GameStateUpdate für $gameId: ${stateUpdate.status}")
          val pseudoState = LichessGameState(stateUpdate.moves, stateUpdate.wtime, stateUpdate.btime, stateUpdate.winc, stateUpdate.binc, stateUpdate.status)
          processGameUpdate(gameId, pseudoState) 
      }
  }

  private def processGameUpdate(gameId: String, lichessState: LichessGameState): Unit = {
    val moves = lichessState.moves.split(" ").filter(_.nonEmpty)
    val moveCount = moves.length
    
    activeGames.get(gameId) match {
      case Some((whiteId, blackId, lastCount)) if moveCount > lastCount =>
        activeGames.put(gameId, (whiteId, blackId, moveCount))
        
        try {
          var currentState: GameState = GameState.initial
          for (moveStr <- moves) {
            val move = CoordinateMoveParser.parse(moveStr, currentState).get
            currentState = GameRules.applyMove(currentState, move)
          }

          if (lichessState.status == "started") {
            val ourId = botUser.map(_.id.toLowerCase).getOrElse("")
            val ourColor = if (whiteId == ourId) Color.White else if (blackId == ourId) Color.Black else null
            
            // Debug turn info
            // println(s"Game $gameId: Active=${currentState.activeColor}, Bot=${ourColor}, BotID=${ourId}")

            if (currentState.activeColor == ourColor) {
              val timeLimit = calculateTimeLimit(currentState, lichessState)
              println(s"Bot am Zug ($gameId [Zug $moveCount])...")
              
              AlphaBetaAgent.bestMove(currentState, timeLimit) match {
                case Some(bestMove) =>
                  val moveUci = bestMove.toInputString
                  client.makeMove(gameId, moveUci).onComplete {
                    case Success(true) => println(s"Zug gesendet: $moveUci")
                    case Success(false) => println(s"Zug $moveUci wurde von Lichess abgelehnt (400).")
                    case Failure(e) => println(s"Netzwerkfehler: ${e.getMessage}")
                  }
                case None =>
                  println(s"Kein legaler Zug für $gameId gefunden.")
              }
            }
          } else {
            activeGames.remove(gameId)
          }
        } catch {
          case e: Exception => 
            println(s"Fehler bei der Zugverarbeitung ($gameId): ${e.getMessage}")
            activeGames.put(gameId, (whiteId, blackId, moveCount - 1))
        }
        
      case Some(_) => // State already seen
      case None => // Waiting for context
    }
  }

  private def calculateTimeLimit(state: GameState, lichess: LichessGameState): Long = {
    val timeLeft = if (state.activeColor == Color.White) lichess.wtime else lichess.btime
    val inc = if (state.activeColor == Color.White) lichess.winc else lichess.binc
    
    // Simple heuristic: 5% of time + increment
    Math.min(10000L, (timeLeft / 20) + inc)
  }
}
