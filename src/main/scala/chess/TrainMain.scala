package chess

import chess.ai.{Evaluator, PassiveTrainer}
import chess.controller.{AppState, GameController, Command}
import scala.concurrent.{Future, Await, ExecutionContext}
import scala.concurrent.duration.Duration
import java.util.concurrent.{Executors, atomic}
import java.io.{File, PrintWriter}
import scala.io.Source
import scala.util.Try

/**
 * Headless training entry point for cloud environments (Google Colab, Kaggle).
 * Runs training synchronously (blocks until all games complete), then saves weights.
 *
 * Every 50,000 games a versioned checkpoint is saved as:
 *   ai_weights_v{totalGames}.txt
 * where totalGames is the CUMULATIVE count across all training sessions.
 * The cumulative counter is persisted in training_meta.txt.
 *
 * Usage: sbt "runMain chess.TrainMain <numGames> [weightsDir]"
 *   - numGames:  number of games to simulate (default: 10000)
 *   - weightsDir: directory for weight files (default: current dir ".")
 */
object TrainMain:

  private val MAX_MOVES_PER_GAME   = 200
  private val CHECKPOINT_INTERVAL  = 50000
  private val META_FILE            = "training_meta.txt"
  private val WEIGHTS_FILE         = "ai_weights.txt"

  // ── Cumulative game counter persistence ──────────────────────────────────

  private def readTotalGames(dir: String): Long =
    val f = File(s"$dir/$META_FILE")
    if f.exists() then
      Try {
        val src = Source.fromFile(f)
        val n = src.getLines().next().trim.toLong
        src.close()
        n
      }.getOrElse(0L)
    else 0L

  private def writeTotalGames(dir: String, total: Long): Unit =
    val out = PrintWriter(File(s"$dir/$META_FILE"))
    out.println(total)
    out.close()

  // ── Main ─────────────────────────────────────────────────────────────────

  def main(args: Array[String]): Unit =
    val numGames   = args.lift(0).flatMap(_.toIntOption).getOrElse(10000)
    val weightsDir = args.lift(1).getOrElse(".")

    val weightsFile   = s"$weightsDir/$WEIGHTS_FILE"
    val previousTotal = readTotalGames(weightsDir)

    println(s"═══════════════════════════════════════════════")
    println(s"  Chess AI Training — Headless Mode")
    println(s"═══════════════════════════════════════════════")
    println(s"  New games:       $numGames")
    println(s"  Previous total:  $previousTotal")
    println(s"  Weights dir:     $weightsDir")
    println(s"  Checkpoint every $CHECKPOINT_INTERVAL games")
    println(s"  Threads:         ${Runtime.getRuntime.availableProcessors()}")
    println(s"═══════════════════════════════════════════════")

    // Load initial weights
    if File(weightsFile).exists() then
      Evaluator.loadWeights(weightsFile)
      println(s"✓ Loaded weights from $weightsFile")
    else
      println(s"⚠ No weights file found — using defaults")

    // Thread pool
    val nThreads = Runtime.getRuntime.availableProcessors()
    val pool = Executors.newFixedThreadPool(nThreads)
    given ec: ExecutionContext = ExecutionContext.fromExecutorService(pool)

    val completed = atomic.AtomicInteger(0)
    val lastCheckpoint = atomic.AtomicLong(0L) // tracks last checkpoint boundary
    val startTime = System.currentTimeMillis()

    // Determine which checkpoint boundaries to hit
    // E.g. if previousTotal=120000 and numGames=200000 → checkpoints at 150k, 200k, 250k, 300k, 320k(final)
    def currentTotal(done: Int): Long = previousTotal + done.toLong

    // Launch all games
    println(s"\n▶ Training started...\n")
    val allFutures = Future.traverse((1 to numGames).toList) { _ =>
      Future {
        simulateGame()
      }.map { _ =>
        val done = completed.incrementAndGet()
        val total = currentTotal(done)

        // Progress log every 500 games
        if done % 500 == 0 || done == numGames then
          val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
          val gps = done / elapsed
          val eta = if gps > 0 then ((numGames - done) / gps).toInt else 0
          println(f"  [$done%6d/$numGames] total=$total | ${elapsed}%.1fs | ${gps}%.1f g/s | ETA ${eta}s")

        // Versioned checkpoint every 50,000 cumulative games
        val checkpointBoundary = (total / CHECKPOINT_INTERVAL) * CHECKPOINT_INTERVAL
        val prevCheckpoint = lastCheckpoint.get()
        if checkpointBoundary > prevCheckpoint && checkpointBoundary >= CHECKPOINT_INTERVAL then
          if lastCheckpoint.compareAndSet(prevCheckpoint, checkpointBoundary) then
            val versionedFile = s"$weightsDir/ai_weights_v${checkpointBoundary}.txt"
            Evaluator.saveWeights(versionedFile)
            Evaluator.saveWeights(weightsFile) // also update main file
            writeTotalGames(weightsDir, total)
            println(s"  💾 Checkpoint: $versionedFile (${checkpointBoundary} total games)")
      }
    }

    // BLOCK until all games finish
    Await.result(allFutures, Duration.Inf)

    // Final save
    val finalTotal = currentTotal(numGames)
    Evaluator.saveWeights(weightsFile)
    Evaluator.saveWeights(s"$weightsDir/ai_weights_v${finalTotal}.txt")
    writeTotalGames(weightsDir, finalTotal)

    val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
    println(s"\n═══════════════════════════════════════════════")
    println(f"  ✅ Training complete! $numGames games in ${totalTime}%.1fs")
    println(s"  📊 Total games trained (all sessions): $finalTotal")
    println(s"  💾 Final weights: $weightsFile")
    println(s"  💾 Versioned:     ai_weights_v${finalTotal}.txt")
    println(s"═══════════════════════════════════════════════")

    pool.shutdown()

  /** Simulate one AI-vs-AI game */
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
