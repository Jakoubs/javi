package chess.ai

import chess.model.*
import java.util.concurrent.ConcurrentHashMap
import scala.util.boundary

/**
 * Advanced Chess AI using Negamax with Alpha-Beta pruning, 
 * Iterative Deepening, Move Ordering, and Quiescence Search.
 */
object AlphaBetaAgent:

  private val MAX_DEPTH = 32
  private val MATE_SCORE = 1000000.0
  
  // Transposition Table Entry
  private case class TTEntry(score: Double, depth: Int, nodeType: Int, move: Option[Move]) // 0: EXACT, 1: LOWER, 2: UPPER
  private val tt = new ConcurrentHashMap[String, TTEntry]()

  // Advanced Heuristics Tables
  private val killers = Array.ofDim[Move](MAX_DEPTH + 1, 2)
  private val history = Array.fill(2, 64, 64)(0) // color, from, to

  private def clearHeuristics(): Unit =
    for i <- 0 to MAX_DEPTH do
      killers(i)(0) = null
      killers(i)(1) = null
    for i <- 0 until 2; j <- 0 until 64; k <- 0 until 64 do
      history(i)(j)(k) = 0

  /**
   * Find the best move within the given time limit.
   */
  def bestMove(state: GameState, timeLimitMs: Long): Option[Move] =
    val startTime = System.currentTimeMillis()
    val deadline = startTime + timeLimitMs
    clearHeuristics()
    val legalMoves = MoveGenerator.legalMoves(state)
    
    if legalMoves.isEmpty then return None
    if legalMoves.size == 1 then return Some(legalMoves.head)

    var bestMove: Option[Move] = None
    var lastDepthBestMove: Option[Move] = None
    
    // Iterative Deepening
    boundary:
      for depth <- 1 to MAX_DEPTH do
        if System.currentTimeMillis() >= deadline - 50 then boundary.break()
        
        val result = searchAtRoot(state, depth, deadline)
        result match
          case Some((move, score)) =>
            bestMove = Some(move)
            lastDepthBestMove = Some(move)
            // If we found checkmate, we can stop
            if Math.abs(score) > MATE_SCORE - 1000 then boundary.break()
          case None => 
            boundary.break() // Time up

    bestMove.orElse(lastDepthBestMove).orElse(legalMoves.headOption)

  private def searchAtRoot(state: GameState, depth: Int, deadline: Long): Option[(Move, Double)] = boundary:
    val moves = orderMoves(MoveGenerator.legalMoves(state), state, depth)
    var bestMove: Option[Move] = None
    var alpha = Double.NegativeInfinity
    val beta = Double.PositiveInfinity
    var bestScore = Double.NegativeInfinity

    for move <- moves do
      if System.currentTimeMillis() >= deadline then boundary.break(None)
      val nextState = GameRules.applyMove(state, move)
      // Negamax: score is -negamax(...)
      val score = -negamax(nextState, depth - 1, -beta, -alpha, deadline)
      
      if score > bestScore then
        bestScore = score
        bestMove = Some(move)
        alpha = Math.max(alpha, score)

    bestMove.map(m => (m, bestScore))

  private def negamax(state: GameState, depth: Int, alpha: Double, beta: Double, deadline: Long): Double = boundary:
    if System.currentTimeMillis() >= deadline then boundary.break(alpha)

    // 1. TT Lookup
    val fen = state.toFen
    val cached = tt.get(fen)
    var currentAlpha = alpha
    if cached != null && cached.depth >= depth then
      cached.nodeType match
        case 0 => boundary.break(cached.score)
        case 1 => currentAlpha = Math.max(currentAlpha, cached.score)
        case 2 => if cached.score <= alpha then boundary.break(cached.score) 
      if currentAlpha >= beta then boundary.break(cached.score)

    // 1.5 Null Move Pruning (NMP)
    if depth >= 3 && !MoveGenerator.isInCheck(state, state.activeColor) && !isEndgame(state) then
      val nextState = state.withActiveColor(state.activeColor.opposite)
      val eval = -negamax(nextState, depth - 1 - 2, -beta, -beta + 1, deadline)
      if eval >= beta then boundary.break(beta)

    // 2. Base Case
    val status = GameRules.computeStatus(state)
    status match
      case GameStatus.Checkmate(_) => boundary.break(-MATE_SCORE - depth) // Prefer faster mate
      case GameStatus.Stalemate | GameStatus.Draw(_) => boundary.break(0.0)
      case _ if depth <= 0 => boundary.break(quiescence(state, currentAlpha, beta, deadline))
      case _ => ()

    // 3. Recursive Search
    val allMoves = MoveGenerator.legalMoves(state)
    val moves = orderMoves(allMoves, state, depth)
    var maxEval = Double.NegativeInfinity
    var bestMoveSeen: Option[Move] = None
    var movesSearched = 0

    boundary:
      for move <- moves do
        if System.currentTimeMillis() >= deadline then boundary.break()
        val nextState = GameRules.applyMove(state, move)
        
        var eval = Double.NegativeInfinity
        
        // Late Move Reductions (LMR)
        if depth >= 3 && movesSearched >= 4 && !isCapture(move, state) && move.promotion.isEmpty && !MoveGenerator.isInCheck(nextState, state.activeColor.opposite) then
          eval = -negamax(nextState, depth - 2, -currentAlpha - 1, -currentAlpha, deadline)
          if eval > currentAlpha then
            // Full search if reduction failed
            eval = -negamax(nextState, depth - 1, -beta, -currentAlpha, deadline)
        else
          eval = -negamax(nextState, depth - 1, -beta, -currentAlpha, deadline)
        
        movesSearched += 1
        
        if eval > maxEval then
          maxEval = eval
          bestMoveSeen = Some(move)
        
        if eval > currentAlpha then
          currentAlpha = eval

        if currentAlpha >= beta then
          // Cutoff: Update heuristics
          if !isCapture(move, state) then
            updateKillers(depth, move)
            updateHistory(state.activeColor, move, depth)
          boundary.break() // Alpha-Beta Cutoff

    // 4. TT Store
    val nodeType = if maxEval <= alpha then 2 else if maxEval >= beta then 1 else 0
    if tt.size() < 100000 then
      tt.put(fen, TTEntry(maxEval, depth, nodeType, bestMoveSeen))

    maxEval

  private def isEndgame(state: GameState): Boolean =
    val pieces = state.board.pieces.values.toList
    val material = pieces.map(p => PieceType.pieceValue(p.pieceType)).sum
    material < 3500

  private def updateKillers(depth: Int, move: Move): Unit =
    if depth <= MAX_DEPTH then
      if killers(depth)(0) != move then
        killers(depth)(1) = killers(depth)(0)
        killers(depth)(0) = move

  private def updateHistory(color: Color, move: Move, depth: Int): Unit =
    val c = if color == Color.White then 0 else 1
    val from = move.from.row * 8 + move.from.col
    val to = move.to.row * 8 + move.to.col
    history(c)(from)(to) += depth * depth
    if history(c)(from)(to) > 1000000 then
      // Reset if too large
      for i <- 0 until 64; j <- 0 until 64 do history(c)(i)(j) /= 2

  private def quiescence(state: GameState, alpha: Double, beta: Double, deadline: Long): Double = boundary:
    if System.currentTimeMillis() >= deadline then boundary.break(alpha)

    val standPat = Evaluator.evaluate(state) * (if state.activeColor == Color.White then 1.0 else -1.0)
    if standPat >= beta then boundary.break(beta)
    var currentAlpha = Math.max(alpha, standPat)

    val moves = orderMoves(MoveGenerator.legalMoves(state).filter(isCapture(_, state)), state, 0)
    
    boundary:
      for move <- moves do
        if System.currentTimeMillis() >= deadline then boundary.break()
        val nextState = GameRules.applyMove(state, move)
        val eval = -quiescence(nextState, -beta, -currentAlpha, deadline)
        
        currentAlpha = Math.max(currentAlpha, eval)
        if currentAlpha >= beta then boundary.break()

    currentAlpha

  private def isCapture(move: Move, state: GameState): Boolean =
    state.board.isOccupied(move.to) || state.enPassantTarget.contains(move.to)

  private def orderMoves(moves: List[Move], state: GameState, depth: Int): List[Move] =
    val pvMove = Option(tt.get(state.toFen)).flatMap(_.move)
    
    moves.sortBy { move =>
      var score = 0
      
      // PV move from TT gets highest priority
      if pvMove.contains(move) then score += 20000
      
      val piece = state.board.get(move.from).map(_.pieceType).getOrElse(PieceType.Pawn)
      val target = state.board.get(move.to).map(_.pieceType)
      
      // MVV-LVA (Most Valuable Victim - Least Valuable Aggressor)
      target match
        case Some(pt) => 
          score += 10000 + 10 * PieceType.pieceValue(pt) - PieceType.pieceValue(piece)
        case None => 
          // Killer moves
          if depth > 0 && depth <= MAX_DEPTH then
            if killers(depth)(0) == move then score += 5000
            else if killers(depth)(1) == move then score += 4000
          
          // History heuristic
          val c = if state.activeColor == Color.White then 0 else 1
          val from = move.from.row * 8 + move.from.col
          val to = move.to.row * 8 + move.to.col
          score += Math.min(3000, history(c)(from)(to) / 100)

      // Promotion bonus
      move.promotion.foreach(pt => score += 12000 + PieceType.pieceValue(pt))

      -score // Sort descending
    }
