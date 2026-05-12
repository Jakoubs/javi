package chess.model

object GameRules:

  /**
   * Apply `move` to `state` and return the new state.
   * Assumes the move is legal (use MoveGenerator.legalMoves to verify first).
   */
  def applyMove(state: GameState, move: Move): GameState =
    val board = state.board
    val piece = board.get(move.from).get   // safe: called after legality check
    val color = piece.color

    val enPassantCapturePos: Pos | Null =
      if piece.pieceType == PieceType.Pawn && state.enPassantTarget.contains(move.to) then
        val capturedRow = if color == Color.White then move.to.row - 1 else move.to.row + 1
        Pos(move.to.col, capturedRow)
      else null

    val (rookFrom, rookTo): (Pos | Null, Pos | Null) =
      if piece.pieceType == PieceType.King then
        val dc = move.to.col - move.from.col
        if math.abs(dc) == 2 then
          val row = move.from.row
          val rookCol = if dc > 0 then 7 else 0
          val newCol = if dc > 0 then 5 else 3
          (Pos(rookCol, row), Pos(newCol, row))
        else (null, null)
      else (null, null)

    val movedPiece = move.promotion match
      case Some(pt) => Piece(color, pt)
      case None     => piece

    val boardAfterMove =
      board.applyMoveUnchecked(
        from = move.from,
        to = move.to,
        movingPiece = piece,
        resultingPiece = movedPiece,
        enPassantCapturePos = enPassantCapturePos,
        rookFrom = rookFrom,
        rookTo = rookTo
      )

    val cr = updateCastlingRights(state.castlingRights, piece, move)
    val newEP =
      if piece.pieceType == PieceType.Pawn && math.abs(move.to.row - move.from.row) == 2 then
        val epRow = (move.from.row + move.to.row) / 2
        Some(Pos(move.from.col, epRow))
      else None

    val isCapture = board.isOccupied(move.to) || state.enPassantTarget.contains(move.to)
    val isPawnMove = piece.pieceType == PieceType.Pawn
    val newHalfClock = if isCapture || isPawnMove then 0 else state.halfMoveClock + 1
    val newFullMove = if color == Color.Black then state.fullMoveNumber + 1 else state.fullMoveNumber
    val capturedPiece =
      if enPassantCapturePos != null then Piece(color.opposite, PieceType.Pawn)
      else board.pieceAtOrNull(move.to)
    val capturedPos =
      if enPassantCapturePos != null then enPassantCapturePos else if capturedPiece != null then move.to else null
    val newPositionHash =
      ZobristHash.advance(
        currentHash = state.positionHash,
        activeColor = color,
        castlingRights = state.castlingRights,
        enPassantTarget = state.enPassantTarget,
        move = move,
        movingPiece = piece,
        resultingPiece = movedPiece,
        capturedPiece = capturedPiece,
        capturedPos = capturedPos,
        newCastlingRights = cr,
        newEnPassantTarget = newEP,
        rookFrom = rookFrom,
        rookTo = rookTo
      )
    val newRepetitionCounts =
      GameState.advanceRepetitionCounts(state.repetitionCounts, newPositionHash, irreversible = isCapture || isPawnMove)

    if color.opposite == Color.White then
      GameState.white(
        boardAfterMove,
        cr,
        newEP,
        newHalfClock,
        newFullMove,
        state.history :+ state,
        positionHash = newPositionHash,
        repetitionCounts = newRepetitionCounts
      )
    else
      GameState.black(
        boardAfterMove,
        cr,
        newEP,
        newHalfClock,
        newFullMove,
        state.history :+ state,
        positionHash = newPositionHash,
        repetitionCounts = newRepetitionCounts
      )

  private def updateCastlingRights(cr: CastlingRights, piece: Piece, move: Move): CastlingRights =
    var updated = cr
    if piece.pieceType == PieceType.King then
      updated = if piece.color == Color.White then updated.disableWhite else updated.disableBlack
    if piece.pieceType == PieceType.Rook then
      move.from match
        case Pos(0, 0) => updated = updated.disableWhiteQueenSide
        case Pos(7, 0) => updated = updated.disableWhiteKingSide
        case Pos(0, 7) => updated = updated.disableBlackQueenSide
        case Pos(7, 7) => updated = updated.disableBlackKingSide
        case _         => ()
    move.to match
      case Pos(0, 0) => updated = updated.disableWhiteQueenSide
      case Pos(7, 0) => updated = updated.disableWhiteKingSide
      case Pos(0, 7) => updated = updated.disableBlackQueenSide
      case Pos(7, 7) => updated = updated.disableBlackKingSide
      case _         => ()
    updated

  def computeStatus(state: GameState): GameStatus =
    val moves = MoveGenerator.legalMoves(state)
    val inCheck = MoveGenerator.isInCheck(state, state.activeColor)

    if moves.isEmpty then
      if inCheck then GameStatus.Checkmate(state.activeColor)
      else GameStatus.Stalemate
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

  private def isFiftyMoveRule(state: GameState): Boolean =
    state.halfMoveClock >= 100

  private def isThreefoldRepetition(state: GameState): Boolean =
    state.repetitionCounts.getOrElse(state.positionHash, 0) >= 3

  private def isInsufficientMaterial(state: GameState): Boolean =
    val pieces = scala.collection.mutable.ListBuffer.empty[Piece]
    state.board.foreachPiece { (_, piece) =>
      pieces += piece
    }
    val types = pieces.map(_.pieceType)
    val counts = types.groupBy(identity).view.mapValues(_.size).toMap

    val kingCount = counts.getOrElse(PieceType.King, 0)
    val totalCount = types.size

    if kingCount < 2 then return true

    if totalCount == 2 then true
    else if totalCount == 3 then
      counts.contains(PieceType.Bishop) || counts.contains(PieceType.Knight)
    else if totalCount == 4 && counts.getOrElse(PieceType.Bishop, 0) == 2 then
      val bishops = scala.collection.mutable.ListBuffer.empty[Pos]
      state.board.foreachPiece { (pos, piece) =>
        if piece.pieceType == PieceType.Bishop then bishops += pos
      }
      val pos1 = bishops(0)
      val pos2 = bishops(1)
      (pos1.row + pos1.col) % 2 == (pos2.row + pos2.col) % 2
    else false
