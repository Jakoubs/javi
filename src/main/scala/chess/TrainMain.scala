package chess

import chess.ai.{Evaluator, PassiveTrainer}
import chess.controller.{AppState, GameController, Command}
import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.Duration
import java.util.concurrent.{Executors, atomic}
import java.io.File

/**
 * Headless training entry point for cloud environments (Google Colab, Kaggle).
 * Runs training synchronously (blocks until all games complete), then saves weights.
 *
 * Usage: sbt "runMain chess.TrainMain <numGames> [weightsPath] [outputPath]"
 *   - numGames:    number of games to simulate (default: 10000)
 *   - weightsPath: path to load initial weights from (default: ai_weights.txt)
 *   - outputPath:  path to save trained weights to (default: ai_weights.txt)
 */
object TrainMain:

  private val MAX_MOVES_PER_GAME = 200

  def main(args: Array[String]): Unit =
    val numGames    = args.lift(0).flatMap(_.toIntOption).getOrElse(10000)
    val weightsIn   = args.lift(1).getOrElse("ai_weights.txt")
    val weightsOut  = args.lift(2).getOrElse("ai_weights.txt")

    println(s"═══════════════════════════════════════════════")
    println(s"  Chess AI Training — Headless Mode")
    println(s"═══════════════════════════════════════════════")
    println(s"  Games:        $numGames")
    println(s"  Weights in:   $weightsIn")
    println(s"  Weights out:  $weightsOut")
    println(s"  Threads:      ${Runtime.getRuntime.availableProcessors()}")
    println(s"═══════════════════════════════════════════════")

    // Load initial weights
    if File(weightsIn).exists() then
      Evaluator.loadWeights(weightsIn)
      println(s"✓ Loaded weights from $weightsIn")
    else
      println(s"⚠ No weights file found at $weightsIn — using defaults")

    // Thread pool
    val nThreads = Runtime.getRuntime.availableProcessors()
    val pool = Executors.newFixedThreadPool(nThreads)
    given ec: ExecutionContext = ExecutionContext.fromExecutorService(pool)

    val completed = atomic.AtomicInteger(0)
    val checkpointInterval = Math.max(numGames / 10, 1000) // save ~10 checkpoints
    val startTime = System.currentTimeMillis()

    // Launch all games
    val allFutures = Future.traverse((1 to numGames).toList) { _ =>
      Future {
        simulateGame()
      }.map { _ =>
        val done = completed.incrementAndGet()
        if done % 500 == 0 || done == numGames then
          val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
          val gps = done / elapsed
          val eta = if gps > 0 then ((numGames - done) / gps).toInt else 0
          println(f"  [$done%6d/$numGames] ${elapsed}%.1fs elapsed | ${gps}%.1f games/s | ETA ${eta}s")
        if done % checkpointInterval == 0 then
          Evaluator.saveWeights(weightsOut)
          println(s"  💾 Checkpoint saved ($done games)")
      }
    }

    // BLOCK until all games finish
    println(s"\n▶ Training started...\n")
    Await.result(allFutures, Duration.Inf)

    // Final save
    Evaluator.saveWeights(weightsOut)
    val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
    println(s"\n═══════════════════════════════════════════════")
    println(f"  ✅ Training complete! $numGames games in ${totalTime}%.1fs")
    println(s"  💾 Weights saved to $weightsOut")
    println(s"═══════════════════════════════════════════════")

    pool.shutdown()

  /** Simulate one AI-vs-AI game (identical to GameController.simulateGame) */
  private def simulateGame(): Unit =
    var gameApp = AppState.initial
    var moveCount = 0
    while gameApp.status == chess.model.GameStatus.Playing && moveCount < MAX_MOVES_PER_GAME do
      val move = chess.ai.AiEngine.bestMove(gameApp.game, 2, epsilon = 0.1)
      move match
        case Some(m) =>
          gameApp = GameController.handleCommand(gameApp, Command.ProcessTurn(m.toInputString))
        case None =>
          gameApp = gameApp.copy(status = chess.model.GameStatus.Draw("no legal moves"))
      moveCount += 1
    if gameApp.status == chess.model.GameStatus.Playing then
      gameApp = gameApp.copy(status = chess.model.GameStatus.Draw("max moves reached"))
