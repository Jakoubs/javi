package chess.ai

import chess.model.*

object AiEngine:

  // Cache key uses FEN (compact board representation without history)
  private val transpositionTable = new java.util.concurrent.ConcurrentHashMap[(String, Int), Double]()
  private val MAX_CACHE_SIZE = 100000

  def clearTranspositionTable(): Unit = transpositionTable.clear()

  /**
   * Find the best move for the active player using Minimax with Alpha-Beta pruning.
   * @param epsilon Probability of choosing a random move (for training/exploration)
   */
  def bestMove(state: GameState, depth: Int, epsilon: Double = 0.0): Option[Move] =
    val legalMoves = MoveGenerator.legalMoves(state)
    if legalMoves.isEmpty then None
    else if epsilon > 0 && scala.util.Random.nextDouble() < epsilon then
      // Epsilon-Greedy: Choose a random legal move
      Some(legalMoves(scala.util.Random.nextInt(legalMoves.length)))
    else
      val isMaximizing = state.activeColor == Color.White
      var bestValue = if isMaximizing then Double.NegativeInfinity else Double.PositiveInfinity
      var bestMove: Option[Move] = None

      for move <- legalMoves do
        val nextState = GameRules.applyMove(state, move)
        val value = minimax(nextState, depth - 1, Double.NegativeInfinity, Double.PositiveInfinity, !isMaximizing)
        
        if isMaximizing then
          if value > bestValue then
            bestValue = value
            bestMove = Some(move)
        else
          if value < bestValue then
            bestValue = value
            bestMove = Some(move)

      bestMove

  private def minimax(state: GameState, depth: Int, alpha: Double, beta: Double, isMaximizing: Boolean): Double =
    // Transposition Table Lookup using FEN (no history, fast hash)
    val cacheKey = (state.toFen, depth)
    if transpositionTable.containsKey(cacheKey) then
      return transpositionTable.get(cacheKey)

    val status = GameRules.computeStatus(state)
    val result: Double = status match
      case GameStatus.Checkmate(loser) =>
        if loser == Color.Black then 1000000.0 else -1000000.0
      case GameStatus.Stalemate | GameStatus.Draw(_) => 0.0
      case _ if depth <= 0 => Evaluator.evaluate(state)
      case _ =>
        val legalMoves = MoveGenerator.legalMoves(state)
        var currentAlpha = alpha
        var currentBeta = beta

        if isMaximizing then
          var maxEval = Double.NegativeInfinity
          scala.util.boundary:
            for move <- legalMoves do
              val nextState = GameRules.applyMove(state, move)
              val eval = minimax(nextState, depth - 1, currentAlpha, currentBeta, false)
              maxEval = Math.max(maxEval, eval)
              currentAlpha = Math.max(currentAlpha, eval)
              if currentBeta <= currentAlpha then scala.util.boundary.break() // Alpha-Beta Pruning
          maxEval
        else
          var minEval = Double.PositiveInfinity
          scala.util.boundary:
            for move <- legalMoves do
              val nextState = GameRules.applyMove(state, move)
              val eval = minimax(nextState, depth - 1, currentAlpha, currentBeta, true)
              minEval = Math.min(minEval, eval)
              currentBeta = Math.min(currentBeta, eval)
              if currentBeta <= currentAlpha then scala.util.boundary.break() // Alpha-Beta Pruning
          minEval

    // Store in Transposition Table (with simple size management)
    if transpositionTable.size() < MAX_CACHE_SIZE then
      transpositionTable.put(cacheKey, result)
    
    result
