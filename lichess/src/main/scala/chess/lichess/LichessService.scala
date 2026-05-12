package chess.lichess

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.*
import chess.model.*
import chess.ai.AlphaBetaAgent
import chess.util.parser.CoordinateMoveParser
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}
import scala.collection.concurrent.TrieMap

class LichessService(client: LichessClient)(implicit system: ActorSystem[?]) {
  implicit val ec: ExecutionContext = system.executionContext
  private var botUser: Option[LichessUser] = None

  private val activeGames = TrieMap.empty[String, (String, String, Int)]
  private val opponentGone = TrieMap.empty[String, Boolean]

  private final case class PonderBundle(
    baseMoveCount: Int,
    baseFen: String,
    expectedMove: String,
    expectedFen: String,
    plannedReply: Option[String],
    depth: Int,
    warmedNodes: Long
  )
  private final case class PonderOutcome(
    status: String,
    warmedNodes: Long,
    expectedMove: String,
    plannedReply: Option[String]
  )
  private val ponderCache = TrieMap.empty[String, PonderBundle]
  private val ponderInFlight = TrieMap.empty[String, String] // gameId -> fen

  def start(): Unit = {
    println("Lichess Service startet... Lade Accountinfo.")
    client.getAccountInfo().onComplete {
      case Success(Some(user)) =>
        botUser = Some(user)
        println(s"Eingeloggt als: ${user.username} (ID: ${user.id})")
        cleanupOngoingGamesAndStart()
      case _ =>
        println("Fehler beim Laden der Accountinfo. Ist der Token korrekt?")
    }
  }

  private def cleanupOngoingGamesAndStart(): Unit = {
    println("Pruefe laufende Spiele zum Aufraeumen...")
    client.getOngoingGameIds().onComplete {
      case Success(gameIds) =>
        if (gameIds.isEmpty) {
          println("Keine laufenden Alt-Spiele gefunden.")
          startEventStream()
        } else {
          println(s"Gefundene laufende Spiele: ${gameIds.mkString(", ")}")
          val resignFutures = gameIds.map { gid =>
            client.resignGame(gid).map(ok => (gid, ok)).recover { case _ => (gid, false) }
          }
          Future.sequence(resignFutures).onComplete {
            case Success(results) =>
              results.foreach { case (gid, ok) =>
                if (ok) println(s"Alt-Spiel resigned: $gid")
                else println(s"Alt-Spiel konnte nicht resigned werden: $gid")
              }
              startEventStream()
            case Failure(e) =>
              println(s"Fehler beim Resign alter Spiele: ${e.getMessage}")
              startEventStream()
          }
        }
      case Failure(e) =>
        println(s"Fehler beim Laden laufender Spiele: ${e.getMessage}")
        startEventStream()
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
          opponentGone.remove(game.id)
          ponderCache.remove(game.id)
          ponderInFlight.remove(game.id)
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
          println(s"GameFull erhalten fuer $gameId")
          val wId = full.white.id.getOrElse("unknown").toLowerCase
          val bId = full.black.id.getOrElse("unknown").toLowerCase
          println(s"Spieler: Weiss=$wId, Schwarz=$bId")
          activeGames.put(gameId, (wId, bId, -1))
          opponentGone.remove(gameId)
          ponderCache.remove(gameId)
          ponderInFlight.remove(gameId)
          processGameUpdate(gameId, full.state)

        case og: OpponentGoneEvent =>
          opponentGone.put(gameId, og.gone)
          if (og.gone) println(s"[Lichess] opponentGone=true => pause Bot auf $gameId")
          else println(s"[Lichess] opponentGone=false => resume Bot auf $gameId")

        case stateUpdate: GameStateUpdateEvent =>
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

            val oppIsGone = opponentGone.getOrElse(gameId, false)
            if (oppIsGone) {
              if (currentState.activeColor == ourColor) println(s"OpponentGone=true und Bot am Zug => warte auf Rueckkehr ($gameId)")
            } else if (currentState.activeColor == ourColor) {
              val timeLimit = calculateTimeLimit(currentState, lichessState)
              val ponderOutcome = ponderCache.get(gameId) match {
                case Some(bundle) if bundle.baseMoveCount + 1 == moveCount && bundle.expectedFen == currentState.toFen =>
                  ponderCache.remove(gameId)
                  Some(PonderOutcome("hit", bundle.warmedNodes, bundle.expectedMove, bundle.plannedReply))
                case Some(bundle) if bundle.baseMoveCount < moveCount =>
                  ponderCache.remove(gameId)
                  Some(PonderOutcome("miss", bundle.warmedNodes, bundle.expectedMove, bundle.plannedReply))
                case _ => None
              }
              println(s"Bot am Zug ($gameId [Zug $moveCount])...")
              val chosenSearch = AlphaBetaAgent.bestMoveWithStats(currentState, timeLimit)
              logPonderOutcome(gameId, ponderOutcome, chosenSearch.map(_.nodes).getOrElse(0L))

              chosenSearch match {
                case Some(result) =>
                  val moveUci = result.move.toInputString
                  client.makeMove(gameId, moveUci).onComplete {
                    case Success(true) => ()
                    case Success(false) => println(s"Zug $moveUci wurde von Lichess abgelehnt (400).")
                    case Failure(e) => println(s"Netzwerkfehler: ${e.getMessage}")
                  }
                case None =>
                  println(s"Kein legaler Zug fuer $gameId gefunden.")
              }
            } else if (ourColor != null) {
              startPonderIfNeeded(gameId, moveCount, currentState, ourColor, totalBudgetMs = 1200L)
            }
          } else {
            activeGames.remove(gameId)
            opponentGone.remove(gameId)
            ponderCache.remove(gameId)
            ponderInFlight.remove(gameId)
          }
        } catch {
          case e: Exception =>
            println(s"Fehler bei der Zugverarbeitung ($gameId): ${e.getMessage}")
            activeGames.put(gameId, (whiteId, blackId, moveCount - 1))
        }

      case Some(_) => // already seen
      case None => // waiting for context
    }
  }

  private def calculateTimeLimit(state: GameState, lichess: LichessGameState): Long = {
    val timeLeft = if (state.activeColor == Color.White) lichess.wtime else lichess.btime
    val inc = if (state.activeColor == Color.White) lichess.winc else lichess.binc
    Math.min(10000L, (timeLeft / 20) + inc)
  }

  private def startPonderIfNeeded(gameId: String, moveCount: Int, state: GameState, ourColor: Color, totalBudgetMs: Long): Unit = {
    val fen = state.toFen
    if ponderCache.get(gameId).exists(p => p.baseMoveCount == moveCount && p.baseFen == fen) then return
    if ponderInFlight.get(gameId).contains(fen) then return

    ponderInFlight.put(gameId, fen)
    Future {
      AlphaBetaAgent.ponderLine(state, totalBudgetMs) match {
        case Some(result) =>
          val expectedMove = result.expectedOpponentMove.toInputString
          val plannedReply = result.plannedReply.map(_.toInputString)
          if activeGames.get(gameId).exists(_._3 == moveCount) then
            ponderCache.put(
              gameId,
              PonderBundle(moveCount, fen, expectedMove, result.expectedFen, plannedReply, result.depth, result.warmedNodes)
            )
        case None =>
          ()
      }
      ponderInFlight.remove(gameId, fen)
    }.recover { case e =>
      ponderInFlight.remove(gameId, fen)
      println(s"[AI][PONDER-ERROR] failed=${e.getMessage}")
    }
    ()
  }

  private def logPonderOutcome(gameId: String, outcome: Option[PonderOutcome], searchNodes: Long): Unit = {
    outcome.foreach { o =>
      val totalRelevantNodes = o.warmedNodes + searchNodes
      val savedPct =
        if (o.status == "hit" && totalRelevantNodes > 0) then
          (o.warmedNodes.toDouble * 100.0) / totalRelevantNodes.toDouble
        else 0.0
      val savedPctText = f"$savedPct%.2f"
      val reply = o.plannedReply.map(r => s" reply=$r").getOrElse("")
      println(
        s"[AI][PONDER-RESULT] ponder=${o.status} warmednodes=${o.warmedNodes} searchnodes=$searchNodes savedPct=$savedPctText expected=${o.expectedMove}$reply"
      )
    }
  }

  private def captureOrderingScore(state: GameState, move: Move): Int = 0
}
