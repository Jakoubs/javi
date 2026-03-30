package chess.model

// ─── GameRules ───────────────────────────────────────────────────────────────

object GameRules:

  // ── Apply a move ───────────────────────────────────────────────────────────

  /**
   * Apply `move` to `state` and return the new state.
   * Assumes the move is legal (use MoveGenerator.legalMoves to verify first).
   */
  def applyMove(state: GameState, move: Move): GameState =
    val board  = state.board
    val piece  = board.get(move.from).get   // safe: called after legality check
    val color  = piece.color

    // ── 1. Handle en-passant capture ──────────────────────────────────────
    val boardAfterEP =
      if piece.pieceType == PieceType.Pawn && state.enPassantTarget.contains(move.to) then
        val capturedRow = if color == Color.White then move.to.row - 1 else move.to.row + 1
        board.remove(Pos(move.to.col, capturedRow))
      else board

    // ── 2. Handle castling (move rook) ────────────────────────────────────
    val boardAfterCastle =
      if piece.pieceType == PieceType.King then
        val dc = move.to.col - move.from.col
        if math.abs(dc) == 2 then
          val row     = move.from.row
          val rookCol = if dc > 0 then 7 else 0
          val newCol  = if dc > 0 then 5 else 3
          boardAfterEP.movePiece(Pos(rookCol, row), Pos(newCol, row))
        else boardAfterEP
      else boardAfterEP

    // ── 3. Move piece (with optional promotion) ───────────────────────────
    val movedPiece = move.promotion match
      case Some(pt) => Piece(color, pt)
      case None     => piece

    val boardAfterMove =
      boardAfterCastle.remove(move.from).put(move.to, movedPiece)

    // ── 4. Update castling rights ─────────────────────────────────────────
    val cr = updateCastlingRights(state.castlingRights, piece, move)

    // ── 5. Update en-passant target ───────────────────────────────────────
    val newEP =
      if piece.pieceType == PieceType.Pawn && math.abs(move.to.row - move.from.row) == 2 then
        val epRow = (move.from.row + move.to.row) / 2
        Some(Pos(move.from.col, epRow))
      else None

    // ── 6. Update clocks ──────────────────────────────────────────────────
    val isCapture    = board.isOccupied(move.to) || state.enPassantTarget.contains(move.to)
    val isPawnMove   = piece.pieceType == PieceType.Pawn
    val newHalfClock = if isCapture || isPawnMove then 0 else state.halfMoveClock + 1
    val newFullMove  = if color == Color.Black then state.fullMoveNumber + 1 else state.fullMoveNumber

    val newState = if color.opposite == Color.White then
      WhiteToMove(boardAfterMove, cr, newEP, newHalfClock, newFullMove, state.history :+ state)
    else
      BlackToMove(boardAfterMove, cr, newEP, newHalfClock, newFullMove, state.history :+ state)

    newState

  // ── Castling rights update ─────────────────────────────────────────────────

  private def updateCastlingRights(cr: CastlingRights, piece: Piece, move: Move): CastlingRights =
    var updated = cr
    // King moved → lose both sides
    if piece.pieceType == PieceType.King then
      updated = if piece.color == Color.White then updated.disableWhite else updated.disableBlack
    // Rook moved from corner
    if piece.pieceType == PieceType.Rook then
      move.from match
        case Pos(0, 0) => updated = updated.disableWhiteQueenSide
        case Pos(7, 0) => updated = updated.disableWhiteKingSide
        case Pos(0, 7) => updated = updated.disableBlackQueenSide
        case Pos(7, 7) => updated = updated.disableBlackKingSide
        case _         => ()
    // Rook captured on corner
    move.to match
      case Pos(0, 0) => updated = updated.disableWhiteQueenSide
      case Pos(7, 0) => updated = updated.disableWhiteKingSide
      case Pos(0, 7) => updated = updated.disableBlackQueenSide
      case Pos(7, 7) => updated = updated.disableBlackKingSide
      case _         => ()
    updated

  // ── Status evaluation ──────────────────────────────────────────────────────

  def computeStatus(state: GameState): GameStatus =
    val moves    = MoveGenerator.legalMoves(state)
    val inCheck  = MoveGenerator.isInCheck(state, state.activeColor)

    if moves.isEmpty then
      if inCheck then GameStatus.Checkmate(state.activeColor)
      else            GameStatus.Stalemate
    else if inCheck then
      GameStatus.Check(state.activeColor)
    else if isFiftyMoveRule(state) then
      GameStatus.Draw("50-move rule")
    else if isThreefoldRepetition(state) then
      GameStatus.Draw("threefold repetition")
    else if isInsufficientMaterial(state) then
      GameStatus.Draw("insufficient material")
    else
      GameStatus.Playing

  // ── Draw detection ─────────────────────────────────────────────────────────

  private def isFiftyMoveRule(state: GameState): Boolean =
    state.halfMoveClock >= 100

  private def isThreefoldRepetition(state: GameState): Boolean =
    // Compare board + active color + castling rights + en-passant (FEN-like key)
    val key = positionKey(state)
    (state :: state.history).count(positionKey(_) == key) >= 3

  private def positionKey(state: GameState): String =
    s"${state.board.toFenPlacement} ${GameState.colorToFen(state.activeColor)} ${state.castlingRights.toFen} ${state.enPassantTarget.map(_.toAlgebraic).getOrElse("-")}"

  private def isInsufficientMaterial(state: GameState): Boolean =
    val pieces = state.board.pieces.values.toList
    val types  = pieces.map(_.pieceType)
    types match
      case List(PieceType.King)                       => true  // KK (shouldn't happen but safe)
      case t if t.count(_ == PieceType.King) == 2 &&
                t.size == 2                           => true  // K vs K
      case t if t.count(_ == PieceType.King) == 2 &&
                t.size == 3 &&
                (t.contains(PieceType.Bishop) || t.contains(PieceType.Knight)) => true  // K+B vs K, K+N vs K
      case _ => false
