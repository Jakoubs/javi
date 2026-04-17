package chess.lichess

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import chess.model.*
import chess.ai.AlphaBetaAgent
import chess.util.parser.CoordinateMoveParser

case class GameContext(whiteId: String, blackId: String, lastMoveCount: Int)

class LichessService(client: LichessClient) {
  
  // Functional state instead of mutable TrieMap
  private type GamesMap = Map[String, GameContext]

  def run(): IO[Unit] = {
    for {
      _ <- IO(println("Lichess Service startet... Lade Accountinfo."))
      userOpt <- client.getAccountInfo()
      _ <- userOpt match {
        case Some(user) =>
          for {
            _ <- IO(println(s"Eingeloggt als: ${user.username} (ID: ${user.id})"))
            gamesRef <- Ref.of[IO, GamesMap](Map.empty)
            _ <- startEventStream(user, gamesRef)
          } yield ()
        case None =>
          IO(println("Fehler beim Laden der Accountinfo. Ist der Token korrekt?"))
      }
    } yield ()
  }

  private def startEventStream(user: LichessUser, gamesRef: Ref[IO, GamesMap]): IO[Unit] = {
    IO(println("Warte auf Lichess-Events...")) *>
    client.streamEvents()
      .evalMap {
        case ChallengeEvent(challenge) =>
          handleChallenge(challenge)
        case GameStartEvent(game) =>
          // Handle game stream in background
          handleGameStart(game.id, user, gamesRef).start.void
        case GameFinishEvent(game) =>
          IO(println(s"Spiel beendet: ${game.id}")) *>
          gamesRef.update(_ - game.id)
        case _ => IO.unit
      }
      .compile
      .drain
  }

  private def handleChallenge(challenge: LichessChallenge): IO[Unit] = {
    if (challenge.variant.key == "standard") {
      IO(println(s"Herausforderung von ${challenge.challenger.map(_.username).getOrElse("Anonym")} akzeptiert.")) *>
      client.acceptChallenge(challenge.id).void
    } else {
      IO(println(s"Herausforderung abgelehnt (Variante: ${challenge.variant.key}).")) *>
      client.declineChallenge(challenge.id).void
    }
  }

  private def handleGameStart(gameId: String, user: LichessUser, gamesRef: Ref[IO, GamesMap]): IO[Unit] = {
    IO(println(s"Spiel-Stream gestartet: $gameId")) *>
    client.streamGame(gameId)
      .evalMap {
        case full: GameFullEvent =>
          val wId = full.white.id.getOrElse("unknown").toLowerCase
          val bId = full.black.id.getOrElse("unknown").toLowerCase
          IO(println(s"GameFull erhalten für $gameId ($wId vs $bId)")) *>
          gamesRef.update(_ + (gameId -> GameContext(wId, bId, -1))) *>
          processGameUpdate(gameId, full.state, user, gamesRef)
          
        case stateUpdate: GameStateUpdateEvent =>
          val pseudoState = LichessGameState(stateUpdate.moves, stateUpdate.wtime, stateUpdate.btime, stateUpdate.winc, stateUpdate.binc, stateUpdate.status)
          processGameUpdate(gameId, pseudoState, user, gamesRef) 
      }
      .compile
      .drain
  }

  private def processGameUpdate(gameId: String, lichessState: LichessGameState, user: LichessUser, gamesRef: Ref[IO, GamesMap]): IO[Unit] = {
    val moves = lichessState.moves.split(" ").filter(_.nonEmpty)
    val moveCount = moves.length
    
    gamesRef.get.flatMap { games =>
      games.get(gameId) match {
        case Some(ctx) if moveCount > ctx.lastMoveCount =>
          for {
            _ <- gamesRef.update(_ + (gameId -> ctx.copy(lastMoveCount = moveCount)))
            _ <- IO.defer {
              IO {
                var currentState: GameState = GameState.initial
                for (moveStr <- moves) {
                  val move = CoordinateMoveParser.parse(moveStr, currentState).get
                  currentState = GameRules.applyMove(currentState, move)
                }

                if (lichessState.status == "started") {
                  val ourId = user.id.toLowerCase
                  val ourColor = if (ctx.whiteId == ourId) Color.White else if (ctx.blackId == ourId) Color.Black else null
                  
                  if (currentState.activeColor == ourColor && ourColor != null) {
                    val timeLimit = calculateTimeLimit(currentState, lichessState)
                    IO(println(s"Bot am Zug ($gameId [Zug $moveCount])...")) *>
                    IO(AlphaBetaAgent.bestMove(currentState, timeLimit)).flatMap {
                      case Some(bestMove) =>
                        val moveUci = bestMove.toInputString
                        client.makeMove(gameId, moveUci).flatMap {
                          case true => IO(println(s"Zug gesendet: $moveUci"))
                          case false => IO(println(s"Zug $moveUci wurde von Lichess abgelehnt."))
                        }
                      case None =>
                        IO(println(s"Kein legaler Zug für $gameId gefunden."))
                    }
                  } else IO.unit
                } else {
                  gamesRef.update(_ - gameId)
                }
              }.flatten
            }.handleErrorWith { e =>
              IO(println(s"Fehler bei der Zugverarbeitung ($gameId): ${e.getMessage}")) *>
              gamesRef.update(_ + (gameId -> ctx)) // restore old count on error maybe?
            }
          } yield ()
          
        case _ => IO.unit // State already seen or waiting for context
      }
    }
  }

  private def calculateTimeLimit(state: GameState, lichess: LichessGameState): Long = {
    val timeLeft = if (state.activeColor == Color.White) lichess.wtime else lichess.btime
    val inc = if (state.activeColor == Color.White) lichess.winc else lichess.binc
    Math.min(10000L, (timeLeft / 20) + inc)
  }
}
