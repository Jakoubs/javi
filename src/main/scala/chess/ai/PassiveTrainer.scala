package chess.ai

import chess.model.*

/**
 * Interface for AI training strategies.
 */
trait Trainer:
  def train(game: GameState, status: GameStatus): Unit

/**
 * Learns from completed games by adjusting evaluation weights.
 */
object PassiveTrainer extends Trainer:

  private val learningRate = 0.5

  /**
   * Called when a game is finished.
   * Adjusts material weights based on the outcome.
   */
  def train(game: GameState, status: GameStatus): Unit =
    status match
      case GameStatus.Checkmate(loser) =>
        val winner = loser.opposite
        val reward = if winner == Color.White then 1.0 else -1.0
        val allStates = game.history :+ game
        updateTrajectory(allStates, reward)
      case GameStatus.Draw(_) | GameStatus.Stalemate =>
        // In case of a draw, we don't necessarily update or we update towards 0
        ()
      case _ => ()

  private def updateTrajectory(states: List[GameState], reward: Double): Unit =
    val trajectoryLr = learningRate / states.size.max(1)
    
    for (state, idx) <- states.zipWithIndex do
      // 1. PST and Material Update for every piece on board in this state
      for (pos, piece) <- state.board.pieces do
        val adjustment = reward * (if piece.color == Color.White then 1.0 else -1.0) * trajectoryLr
        Evaluator.updateWeights(adjustment, piece.pieceType, piece.color, Some(pos))
        
      // 2. Opening Heuristics Update (only makes sense in first ~15 full moves = 30 plies)
      if idx <= 30 then
        updateOpeningWeightsForState(state, reward, Color.White, trajectoryLr)
        updateOpeningWeightsForState(state, reward, Color.Black, trajectoryLr)

  private def updateOpeningWeightsForState(state: GameState, reward: Double, color: Color, lr: Double): Unit =
    val board = state.board
    val startRow = if color == Color.White then 0 else 7
    val pawnRow = if color == Color.White then 1 else 6
    val forward = if color == Color.White then 1 else -1

    // R is positive if this color won. So if White won, White's features should be reinforced.
    val r = if color == Color.White then reward else -reward

    // Center Pawns (+ bonus)
    if board.get(Pos(4, startRow + 3 * forward)).contains(Piece(color, PieceType.Pawn)) then
      Evaluator.updateGlobalWeight(Evaluator.getOpeningCenterPawn, r * lr * 10)
    if board.get(Pos(3, startRow + 3 * forward)).contains(Piece(color, PieceType.Pawn)) then
      Evaluator.updateGlobalWeight(Evaluator.getOpeningCenterPawn, r * lr * 10)
      
    // Center Knights (+ bonus)
    for c <- Seq(2, 5) do
      if board.get(Pos(c, startRow + 2 * forward)).contains(Piece(color, PieceType.Knight)) then
        Evaluator.updateGlobalWeight(Evaluator.getOpeningCenterKnight, r * lr * 10)

    // Undeveloped Minors (- penalty)
    var undevelopedMinors = 0
    if board.get(Pos(1, startRow)).contains(Piece(color, PieceType.Knight)) then undevelopedMinors += 1
    if board.get(Pos(6, startRow)).contains(Piece(color, PieceType.Knight)) then undevelopedMinors += 1
    if board.get(Pos(2, startRow)).contains(Piece(color, PieceType.Bishop)) then undevelopedMinors += 1
    if board.get(Pos(5, startRow)).contains(Piece(color, PieceType.Bishop)) then undevelopedMinors += 1
    
    // If it's a penalty, and the player WON despite it, the penalty was too harsh -> decrease weight (r > 0 -> -r * lr).
    // If the player LOST and exhibited this, the penalty was justified -> increase weight (r < 0 -> -r * lr is positive).
    if undevelopedMinors > 0 then
      Evaluator.updateGlobalWeight(Evaluator.getOpeningUndevelopedMinor, -r * lr * 10 * undevelopedMinors)

    // Early Queen (- penalty)
    if !board.get(Pos(3, startRow)).contains(Piece(color, PieceType.Queen)) && undevelopedMinors > 1 then
      Evaluator.updateGlobalWeight(Evaluator.getOpeningEarlyQueen, -r * lr * 10)

    // King Safety
    val kingPos = board.findKing(color)
    val castlingRights = state.castlingRights
    val kSideAllowed = if color == Color.White then castlingRights.whiteKingSide else castlingRights.blackKingSide
    val qSideAllowed = if color == Color.White then castlingRights.whiteQueenSide else castlingRights.blackQueenSide
    
    val kingMoved = kingPos.exists(p => p != Pos(4, startRow))
    if kingMoved then
      if kingPos.exists(p => p.col == 6 || p.col == 2) then
        Evaluator.updateGlobalWeight(Evaluator.getOpeningCastledBonus, r * lr * 10)
      else if !kSideAllowed && !qSideAllowed then
        Evaluator.updateGlobalWeight(Evaluator.getOpeningLostCastle, -r * lr * 10)
    else if !kSideAllowed && !qSideAllowed then
      Evaluator.updateGlobalWeight(Evaluator.getOpeningUncastled, -r * lr * 10)

    // Connected Rooks (+ bonus)
    if board.get(Pos(0, startRow)).contains(Piece(color, PieceType.Rook)) &&
       board.get(Pos(7, startRow)).contains(Piece(color, PieceType.Rook)) then
         val anyPieceBetween = (1 to 6).map(c => board.get(Pos(c, startRow))).exists(_.isDefined)
         if !anyPieceBetween then Evaluator.updateGlobalWeight(Evaluator.getOpeningConnectedRooks, r * lr * 10)
