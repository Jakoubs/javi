package chess.lichess

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.*
import chess.model.*
import chess.ai2.book.NoOpeningBook
import chess.ai2.core.{Ai2Engine, SearchContext, SearchLimits}
import chess.ai2.mcts.{InMemoryTranspositionTable, MctsConfig}
import chess.ai2.nn.HceBootstrappedPolicyValueNet
import chess.ai2.tablebase.NoEndgameOracle
import chess.persistence.PersistenceModule
import chess.util.parser.CoordinateMoveParser
import cats.effect.unsafe.implicits.global

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class LichessService(client: LichessClient)(implicit system: ActorSystem[?]) {
  implicit val ec: ExecutionContext = system.executionContext
  private var botUser: Option[LichessUser] = None
  private val serviceStartedAtMs: Long = System.currentTimeMillis()
  private val startupGameGraceMs: Long = 15000L

  private val aiEngine = new Ai2Engine(
    openingBook = NoOpeningBook,
    endgameOracle = NoEndgameOracle,
    net = HceBootstrappedPolicyValueNet.default,
    tt = new InMemoryTranspositionTable(),
    mctsConfig = MctsConfig()
  )

  private val activeGames = scala.collection.concurrent.TrieMap.empty[String, (String, String, Int)]
  private val lastSeenMove = scala.collection.concurrent.TrieMap.empty[String, Option[Move]]
  private val openingMaxPly = 20

  private val persistenceOpt: Option[PersistenceModule] =
    try Some(PersistenceModule.build().unsafeRunSync())
    catch
      case e: Throwable =>
        println(s"[OpeningDB] Persistence init failed: ${e.getMessage}")
        None

  // Only one pondering worker is currently supported by Ai2Engine/MctsSearch.
  private val ponderLock = Object()
  @volatile private var activePonderGameId: Option[String] = None

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
        case ChallengeDeclinedEvent(challenge) =>
          println(s"Herausforderung abgelehnt: ${challenge.id}")
        case ChallengeCanceledEvent(challenge) =>
          println(s"Herausforderung abgebrochen: ${challenge.id}")
        case GameStartEvent(game) =>
          handleGameStart(game.id)
        case GameFinishEvent(game) =>
          println(s"Spiel beendet Event: ${game.id}")
          cleanupGame(game.id)
      }
      .onComplete {
        case Success(_) =>
          stopAnyPonder()
          println("Event stream closed.")
        case Failure(e) =>
          stopAnyPonder()
          println(s"Event stream error: ${e.getMessage}")
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
          val isStale = full.createdAt.exists(_ < serviceStartedAtMs - startupGameGraceMs)
          if isStale then
            println(s"Ignoriere altes Spiel beim Start: $gameId")
            cleanupGame(gameId)
          else
            println(s"GameFull erhalten fuer $gameId")
            val wId = full.white.id.getOrElse("unknown").toLowerCase
            val bId = full.black.id.getOrElse("unknown").toLowerCase
            println(s"Spieler: Weiss=$wId, Schwarz=$bId")
            activeGames.put(gameId, (wId, bId, -1))
            lastSeenMove.put(gameId, None)
            processGameUpdate(gameId, full.state)

        case stateUpdate: GameStateUpdateEvent =>
          val pseudoState = LichessGameState(stateUpdate.moves, stateUpdate.wtime, stateUpdate.btime, stateUpdate.winc, stateUpdate.binc, stateUpdate.status)
          processGameUpdate(gameId, pseudoState)
        case ChatLineEvent =>
          ()
        case OpponentGoneEvent =>
          ()
      }
      .onComplete { _ =>
        // Safety net in case stream closes without explicit finish event.
        cleanupGame(gameId)
      }
  }

  private def processGameUpdate(gameId: String, lichessState: LichessGameState): Unit = {
    val moves = lichessState.moves.split(" ").filter(_.nonEmpty)
    val moveCount = moves.length
    println(s"[GameState][$gameId] status=${lichessState.status} moves=$moveCount")

    activeGames.get(gameId) match {
      case Some((whiteId, blackId, lastCount)) if moveCount > lastCount =>
        activeGames.put(gameId, (whiteId, blackId, moveCount))

        try {
          var currentState: GameState = GameState.initial
          var parsedMoves = List.empty[Move]
          for (moveStr <- moves) {
            val move = CoordinateMoveParser.parse(moveStr, currentState).get
            parsedMoves = parsedMoves :+ move
            currentState = GameRules.applyMove(currentState, move)
          }

          val lastOppMove = parsedMoves.lastOption
          lastSeenMove.put(gameId, lastOppMove)

          if (lichessState.status == "started") {
            val ourId = botUser.map(_.id.toLowerCase).getOrElse("")
            val ourColorOpt =
              if (whiteId == ourId) Some(Color.White)
              else if (blackId == ourId) Some(Color.Black)
              else None

            ourColorOpt match {
              case None =>
                println(s"Bot-Farbe konnte nicht bestimmt werden ($gameId, white=$whiteId, black=$blackId, ourId=$ourId).")
              case Some(ourColor) if currentState.activeColor == ourColor =>
                stopPonderForGame(gameId)
                val timeLimit = calculateTimeLimit(currentState, lichessState)
                println(s"Bot am Zug ($gameId [Zug $moveCount])...")

                val openingMove =
                  if moveCount < openingMaxPly then pickOpeningMove(currentState)
                  else None

                val inOpeningPhase = moveCount < openingMaxPly

                openingMove match {
                  case Some((bookMove, source, score)) =>
                    val moveUci = bookMove.toInputString
                    println(s"[OpeningDB][$gameId][ply=$moveCount] source=$source weight=$score play=$moveUci")
                    client.makeMove(gameId, moveUci).onComplete {
                      case Success(true) => println(s"Zug gesendet: $moveUci")
                      case Success(false) => println(s"Zug $moveUci wurde von Lichess abgelehnt (400).")
                      case Failure(e) => println(s"Netzwerkfehler: ${e.getMessage}")
                    }

                  case None =>
                    if inOpeningPhase then
                      pickOpeningFallbackMove(currentState) match
                        case Some(bestMove) =>
                          val moveUci = bestMove.toInputString
                          println(s"[OpeningDB][$gameId][ply=$moveCount] fallback=legal-first play=$moveUci")
                          client.makeMove(gameId, moveUci).onComplete {
                            case Success(true) => println(s"Zug gesendet: $moveUci")
                            case Success(false) => println(s"Zug $moveUci wurde von Lichess abgelehnt (400).")
                            case Failure(e) => println(s"Netzwerkfehler: ${e.getMessage}")
                          }
                        case None =>
                          println(s"Kein legaler Zug fuer $gameId gefunden.")
                    else
                      val result = aiEngine.findBestMove(
                        currentState,
                        SearchLimits(
                          hardTimeMs = timeLimit,
                          softTimeMs = (timeLimit * 0.7).toLong,
                          maxNodes = 300000,
                          ponder = true
                        ),
                        SearchContext(
                          isTraining = false,
                          moveNumber = moveCount,
                          lastOpponentMove = lastOppMove
                        )
                      )
                      logSearch(gameId, moveCount, result)

                      result.bestMove match {
                        case Some(bestMove) =>
                          val moveUci = bestMove.toInputString
                          client.makeMove(gameId, moveUci).onComplete {
                            case Success(true) =>
                              println(s"Zug gesendet: $moveUci")
                              val nextState = GameRules.applyMove(currentState, bestMove)
                              if moveCount >= openingMaxPly then
                                startPonderForGame(
                                  gameId,
                                  nextState,
                                  SearchContext(
                                    isTraining = false,
                                    moveNumber = moveCount + 1,
                                    lastOpponentMove = Some(bestMove)
                                  )
                                )
                            case Success(false) =>
                              println(s"Zug $moveUci wurde von Lichess abgelehnt (400).")
                              stopPonderForGame(gameId)
                            case Failure(e) =>
                              println(s"Netzwerkfehler: ${e.getMessage}")
                              stopPonderForGame(gameId)
                          }
                        case None =>
                          println(s"Kein legaler Zug fuer $gameId gefunden.")
                      }
                }
              case Some(otherColor) =>
                println(s"[Turn][$gameId] Nicht am Zug. active=${currentState.activeColor} ours=$otherColor")
            }
          } else {
            cleanupGame(gameId)
          }
        } catch {
          case e: Exception =>
            println(s"Fehler bei der Zugverarbeitung ($gameId): ${e.getMessage}")
            activeGames.put(gameId, (whiteId, blackId, moveCount - 1))
            stopPonderForGame(gameId)
        }

      case Some(_) => // State already seen
      case None => // Waiting for context
    }
  }

  private def startPonderForGame(gameId: String, state: GameState, ctx: SearchContext): Unit =
    ponderLock.synchronized {
      stopAnyPonderUnsafe()
      activePonderGameId = Some(gameId)
      aiEngine.startPonder(state, ctx)
    }

  private def stopPonderForGame(gameId: String): Unit =
    ponderLock.synchronized {
      if activePonderGameId.contains(gameId) then
        aiEngine.stopPonder()
        activePonderGameId = None
    }

  private def stopAnyPonder(): Unit =
    ponderLock.synchronized {
      stopAnyPonderUnsafe()
    }

  private def stopAnyPonderUnsafe(): Unit =
    aiEngine.stopPonder()
    activePonderGameId = None

  private def cleanupGame(gameId: String): Unit = {
    activeGames.remove(gameId)
    lastSeenMove.remove(gameId)
    stopPonderForGame(gameId)
  }

  private def fenCore(fen: String): String =
    fen.split("\\s+").take(4).mkString(" ")

  private def pickOpeningMove(state: GameState): Option[(Move, String, Int)] =
    persistenceOpt.flatMap { persistence =>
      val exactFen = state.toFen
      val core = fenCore(exactFen)

      val exactRows = persistence.openingDao.findByFen(exactFen).unsafeRunSync()
      val coreRows =
        if exactRows.nonEmpty then exactRows
        else persistence.openingDao.findByFenCore(core).unsafeRunSync()

      val aggregated = coreRows
        .groupBy(_.move)
        .view
        .mapValues(rows => rows.map(r => math.max(1, r.weight)).sum)
        .toList
        .sortBy { case (move, totalWeight) => (-totalWeight, move) }

      val source = if exactRows.nonEmpty then "exact-fen" else "fen-core"

      val picked =
        aggregated.iterator
          .map { case (moveUci, totalWeight) =>
            CoordinateMoveParser.parse(moveUci, state).toOption.map(m => (m, source, totalWeight))
          }
          .collectFirst { case Some(value) => value }

      if picked.isEmpty then
        println(s"[OpeningDB][miss] fen=$exactFen core=$core exactRows=${exactRows.size} coreRows=${coreRows.size}")
      picked
    }

  private def pickOpeningFallbackMove(state: GameState): Option[Move] =
    MoveGenerator.legalMoves(state).headOption

  private def logSearch(gameId: String, moveCount: Int, result: chess.ai2.core.SearchResult): Unit = {
    val source =
      if result.fromBook then "BOOK"
      else if result.fromTablebase then "TB"
      else "MCTS"

    val best = result.bestMove.map(_.toInputString).getOrElse("none")
    val pv = result.principalVariation.take(4).map(_.toInputString).mkString(" ")
    val pvOut = if pv.nonEmpty then pv else "-"

    println(
      f"[AI][$gameId][ply=$moveCount] src=$source best=$best eval=${result.scoreCp}%+dcp nodes=${result.nodes}%d ttHits=${result.ttHits}%d nps=${result.nps}%d depth=${result.depth}%d time=${result.elapsedMs}%dms pv=[$pvOut]"
    )
  }

  private def calculateTimeLimit(state: GameState, lichess: LichessGameState): Long = {
    val timeLeft = if (state.activeColor == Color.White) lichess.wtime else lichess.btime
    val inc = if (state.activeColor == Color.White) lichess.winc else lichess.binc

    val base = (timeLeft / 24) + (inc * 3 / 4)
    val extra = Math.min(timeLeft / 8, (base * 3) / 5)
    val think = base + extra

    Math.max(50L, Math.min(15000L, think))
  }
}
