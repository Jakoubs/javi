package chess.model

object MoveGenerator:

  private val sliderDirections: Array[(Int, Int)] =
    Array((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1))
  private val rookDirectionIndices: Array[Int] = Array(0, 1, 2, 3)
  private val bishopDirectionIndices: Array[Int] = Array(4, 5, 6, 7)
  private val sliderDirectionAscending: Array[Boolean] = Array(true, false, true, false, true, false, true, false)
  private val knightDeltas: Array[(Int, Int)] =
    Array((2, 1), (2, -1), (-2, 1), (-2, -1), (1, 2), (1, -2), (-1, 2), (-1, -2))
  private val kingDirections: Array[(Int, Int)] =
    Array((1, 1), (1, -1), (-1, 1), (-1, -1), (1, 0), (-1, 0), (0, 1), (0, -1))
  private val knightAttackMasks: Array[Long] = precomputeAttackMasks(knightDeltas)
  private val kingAttackMasks: Array[Long] = precomputeAttackMasks(kingDirections)
  private val whitePawnAttackSourceMasks: Array[Long] = precomputePawnAttackSourceMasks(Color.White)
  private val blackPawnAttackSourceMasks: Array[Long] = precomputePawnAttackSourceMasks(Color.Black)
  private val sliderRays: Array[Array[Long]] = precomputeSliderRays()

  def legalMoves(state: GameState): List[Move] =
    val pseudo = pseudoLegalMoves(state)
    val legal = scala.collection.mutable.ListBuffer.empty[Move]
    val kingPos = state.kingPos(state.activeColor)
    val inCheck = kingPos.exists(pos => isAttackedBy(state.board, pos, state.activeColor.opposite))
    var i = 0
    while i < pseudo.length do
      val move = pseudo(i)
      if !leavesKingInCheck(state, move, kingPos, inCheck) then legal += move
      i += 1
    legal.toList

  def legalMovesFrom(state: GameState, from: Pos): List[Move] =
    state.board.get(from) match
      case Some(piece) if piece.color == state.activeColor =>
        val pseudo = scala.collection.mutable.ArrayBuffer.empty[Move]
        addPseudoMovesForPiece(pseudo, state, from, piece)
        val legal = scala.collection.mutable.ListBuffer.empty[Move]
        val kingPos = state.kingPos(state.activeColor)
        val inCheck = kingPos.exists(pos => isAttackedBy(state.board, pos, state.activeColor.opposite))
        var i = 0
        while i < pseudo.length do
          val move = pseudo(i)
          if !leavesKingInCheck(state, move, kingPos, inCheck) then legal += move
          i += 1
        legal.toList
      case _ => Nil

  def isInCheck(state: GameState, color: Color): Boolean =
    state.kingPos(color) match
      case None => false
      case Some(pos) => isAttackedBy(state.board, pos, color.opposite)

  private def pseudoLegalMoves(state: GameState): List[Move] =
    val board = state.board
    val color = state.activeColor
    val moves = scala.collection.mutable.ArrayBuffer.empty[Move]
    pawnMovesFromBitboard(moves, state, color, board.bitboardOf(color, PieceType.Pawn))
    knightMovesFromBitboard(moves, board, color, board.bitboardOf(color, PieceType.Knight))
    slidingMovesFromBitboard(moves, board, color, board.bitboardOf(color, PieceType.Bishop), bishopDirectionIndices)
    slidingMovesFromBitboard(moves, board, color, board.bitboardOf(color, PieceType.Rook), rookDirectionIndices)
    queenMovesFromBitboard(moves, board, color, board.bitboardOf(color, PieceType.Queen))
    kingMovesFromBitboard(moves, state, color, board.bitboardOf(color, PieceType.King))
    moves.toList

  private def addPseudoMovesForPiece(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    pos: Pos,
    piece: Piece
  ): Unit =
    piece.pieceType match
      case PieceType.Pawn =>
        pawnMoves(out, state, pos, piece.color)
      case PieceType.Knight =>
        appendKnightMovesFromSquare(out, state.board, pos, piece.color)
      case PieceType.Bishop =>
        appendSlidingMovesFromSquare(out, state.board, pos, piece.color, bishopDirectionIndices)
      case PieceType.Rook =>
        appendSlidingMovesFromSquare(out, state.board, pos, piece.color, rookDirectionIndices)
      case PieceType.Queen =>
        appendSlidingMovesFromSquare(out, state.board, pos, piece.color, rookDirectionIndices)
        appendSlidingMovesFromSquare(out, state.board, pos, piece.color, bishopDirectionIndices)
      case PieceType.King =>
        appendKingMovesFromSquare(out, state, pos, piece.color)

  private def pawnMovesFromBitboard(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    color: Color,
    pawnsBb: Long
  ): Unit =
    var bb = pawnsBb
    while bb != 0L do
      val idx = java.lang.Long.numberOfTrailingZeros(bb)
      pawnMoves(out, state, Board.posFromSquareIndex(idx), color)
      bb &= (bb - 1L)

  private def knightMovesFromBitboard(
    out: scala.collection.mutable.ArrayBuffer[Move],
    board: Board,
    color: Color,
    knightsBb: Long
  ): Unit =
    var bb = knightsBb
    while bb != 0L do
      val idx = java.lang.Long.numberOfTrailingZeros(bb)
      appendKnightMovesFromSquare(out, board, Board.posFromSquareIndex(idx), color)
      bb &= (bb - 1L)

  private def slidingMovesFromBitboard(
    out: scala.collection.mutable.ArrayBuffer[Move],
    board: Board,
    color: Color,
    piecesBb: Long,
    directionIndices: Array[Int]
  ): Unit =
    var bb = piecesBb
    while bb != 0L do
      val idx = java.lang.Long.numberOfTrailingZeros(bb)
      appendSlidingMovesFromSquare(out, board, Board.posFromSquareIndex(idx), color, directionIndices)
      bb &= (bb - 1L)

  private def queenMovesFromBitboard(
    out: scala.collection.mutable.ArrayBuffer[Move],
    board: Board,
    color: Color,
    queensBb: Long
  ): Unit =
    var bb = queensBb
    while bb != 0L do
      val idx = java.lang.Long.numberOfTrailingZeros(bb)
      val from = Board.posFromSquareIndex(idx)
      appendSlidingMovesFromSquare(out, board, from, color, rookDirectionIndices)
      appendSlidingMovesFromSquare(out, board, from, color, bishopDirectionIndices)
      bb &= (bb - 1L)

  private def kingMovesFromBitboard(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    color: Color,
    kingBb: Long
  ): Unit =
    if kingBb != 0L then
      val idx = java.lang.Long.numberOfTrailingZeros(kingBb)
      appendKingMovesFromSquare(out, state, Board.posFromSquareIndex(idx), color)

  private def pawnMoves(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    pos: Pos,
    color: Color
  ): Unit =
    val dir = if color == Color.White then 1 else -1
    val startRow = if color == Color.White then 1 else 6
    val promRow = if color == Color.White then 7 else 0
    val board = state.board
    def addMoves(to: Pos): Unit =
      if to.row == promRow then
        out += Move(pos, to, Some(PieceType.Queen))
        out += Move(pos, to, Some(PieceType.Rook))
        out += Move(pos, to, Some(PieceType.Bishop))
        out += Move(pos, to, Some(PieceType.Knight))
      else
        out += Move(pos, to)

    val oneStep = pos + (0, dir)
    if oneStep.isValid && board.isEmpty(oneStep) then
      addMoves(oneStep)

    val twoStep = pos + (0, 2 * dir)
    if pos.row == startRow && board.isEmpty(oneStep) && board.isEmpty(twoStep) then
      out += Move(pos, twoStep)

    val leftTarget = pos + (-1, dir)
    if leftTarget.isValid then
      if board.isOccupiedBy(leftTarget, color.opposite) then addMoves(leftTarget)
      else if state.enPassantTarget.contains(leftTarget) then out += Move(pos, leftTarget)

    val rightTarget = pos + (1, dir)
    if rightTarget.isValid then
      if board.isOccupiedBy(rightTarget, color.opposite) then addMoves(rightTarget)
      else if state.enPassantTarget.contains(rightTarget) then out += Move(pos, rightTarget)

  private def appendKnightMovesFromSquare(
    out: scala.collection.mutable.ArrayBuffer[Move],
    board: Board,
    from: Pos,
    color: Color
  ): Unit =
    var attacks = knightAttackMasks(Board.squareIndex(from)) & ~board.colorMask(color)
    while attacks != 0L do
      val toIdx = java.lang.Long.numberOfTrailingZeros(attacks)
      out += Move(from, Board.posFromSquareIndex(toIdx))
      attacks &= (attacks - 1L)

  private def appendSlidingMovesFromSquare(
    out: scala.collection.mutable.ArrayBuffer[Move],
    board: Board,
    from: Pos,
    color: Color,
    directionIndices: Array[Int]
  ): Unit =
    var attacks = sliderAttackMask(Board.squareIndex(from), board.occupiedMask, directionIndices) & ~board.colorMask(color)
    while attacks != 0L do
      val toIdx = java.lang.Long.numberOfTrailingZeros(attacks)
      out += Move(from, Board.posFromSquareIndex(toIdx))
      attacks &= (attacks - 1L)

  private def appendKingMovesFromSquare(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    from: Pos,
    color: Color
  ): Unit =
    var attacks = kingAttackMasks(Board.squareIndex(from)) & ~state.board.colorMask(color)
    while attacks != 0L do
      val toIdx = java.lang.Long.numberOfTrailingZeros(attacks)
      out += Move(from, Board.posFromSquareIndex(toIdx))
      attacks &= (attacks - 1L)
    addCastlingMoves(out, state, from, color)

  // $COVERAGE-OFF$
  private def addCastlingMoves(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    kingPos: Pos,
    color: Color
  ): Unit =
    val board = state.board
    val rights = state.castlingRights
    val row = if color == Color.White then 0 else 7

    if isAttackedBy(board, kingPos, color.opposite) then
      return

    val kingSideAllowed = if color == Color.White then rights.whiteKingSide else rights.blackKingSide
    if kingSideAllowed &&
      board.get(Pos(7, row)).contains(Piece(color, PieceType.Rook)) &&
      board.isEmpty(Pos(5, row)) &&
      board.isEmpty(Pos(6, row)) &&
      !isAttackedBy(board, Pos(5, row), color.opposite) &&
      !isAttackedBy(board, Pos(6, row), color.opposite)
    then
      out += Move(kingPos, Pos(6, row))

    val queenSideAllowed = if color == Color.White then rights.whiteQueenSide else rights.blackQueenSide
    if queenSideAllowed &&
      board.get(Pos(0, row)).contains(Piece(color, PieceType.Rook)) &&
      board.isEmpty(Pos(1, row)) &&
      board.isEmpty(Pos(2, row)) &&
      board.isEmpty(Pos(3, row)) &&
      !isAttackedBy(board, Pos(3, row), color.opposite) &&
      !isAttackedBy(board, Pos(4, row), color.opposite)
    then
      out += Move(kingPos, Pos(2, row))

  def isAttackedBy(board: Board, pos: Pos, attacker: Color): Boolean =
    val idx = Board.squareIndex(pos)
    val pawnSourceMask =
      if attacker == Color.White then whitePawnAttackSourceMasks(idx)
      else blackPawnAttackSourceMasks(idx)
    val pawnAttacked = (board.bitboardOf(attacker, PieceType.Pawn) & pawnSourceMask) != 0L
    val knightAttacked = (board.bitboardOf(attacker, PieceType.Knight) & knightAttackMasks(idx)) != 0L
    val kingAttacked = (board.bitboardOf(attacker, PieceType.King) & kingAttackMasks(idx)) != 0L
    val bishopLikeAttacked =
      (sliderAttackMask(idx, board.occupiedMask, bishopDirectionIndices) &
        (board.bitboardOf(attacker, PieceType.Bishop) | board.bitboardOf(attacker, PieceType.Queen))) != 0L
    val rookLikeAttacked =
      (sliderAttackMask(idx, board.occupiedMask, rookDirectionIndices) &
        (board.bitboardOf(attacker, PieceType.Rook) | board.bitboardOf(attacker, PieceType.Queen))) != 0L

    pawnAttacked || knightAttacked || kingAttacked || bishopLikeAttacked || rookLikeAttacked

  private def leavesKingInCheck(state: GameState, move: Move, kingPos: Option[Pos], inCheck: Boolean): Boolean =
    kingPos match
      case Some(kp) if !inCheck && move.from != kp && !sharesLine(kp, move.from) =>
        // Moving a piece that is not aligned with our king cannot expose a slider check.
        false
      case _ =>
        leavesKingInCheckSlow(state, move)

  private def leavesKingInCheckSlow(state: GameState, move: Move): Boolean =
    val nextBoard = applyMoveToBoard(state, move)
    nextBoard.findKing(state.activeColor).exists(pos => isAttackedBy(nextBoard, pos, state.activeColor.opposite))

  private def sharesLine(a: Pos, b: Pos): Boolean =
    a.row == b.row || a.col == b.col || math.abs(a.row - b.row) == math.abs(a.col - b.col)

  private def applyMoveToBoard(state: GameState, move: Move): Board =
    val board = state.board
    val piece = board.get(move.from).get

    val boardAfterEP =
      if piece.pieceType == PieceType.Pawn && state.enPassantTarget.contains(move.to) then
        val dir = if piece.color == Color.White then -1 else 1
        board.remove(move.to + (0, dir))
      else board

    val boardAfterCastle =
      if piece.pieceType == PieceType.King then
        val dc = move.to.col - move.from.col
        if math.abs(dc) == 2 then
          val row = move.from.row
          val rookCol = if dc > 0 then 7 else 0
          val newCol = if dc > 0 then 5 else 3
          boardAfterEP.movePiece(Pos(rookCol, row), Pos(newCol, row))
        else boardAfterEP
      else boardAfterEP

    val finalPiece = move.promotion match
      case Some(pt) => Piece(piece.color, pt)
      case None => piece

    boardAfterCastle.remove(move.from).put(move.to, finalPiece)
  // $COVERAGE-ON$

  private def precomputeAttackMasks(deltas: Array[(Int, Int)]): Array[Long] =
    Array.tabulate(64) { idx =>
      val from = Board.posFromSquareIndex(idx)
      var mask = 0L
      var i = 0
      while i < deltas.length do
        val (dc, dr) = deltas(i)
        val to = from + (dc, dr)
        if to.isValid then mask |= Board.squareMask(to)
        i += 1
      mask
    }

  private def precomputePawnAttackSourceMasks(color: Color): Array[Long] =
    Array.tabulate(64) { idx =>
      val target = Board.posFromSquareIndex(idx)
      val sourceRow = if color == Color.White then target.row - 1 else target.row + 1
      var mask = 0L
      val left = Pos(target.col - 1, sourceRow)
      val right = Pos(target.col + 1, sourceRow)
      if left.isValid then mask |= Board.squareMask(left)
      if right.isValid then mask |= Board.squareMask(right)
      mask
    }

  private def precomputeSliderRays(): Array[Array[Long]] =
    Array.tabulate(64) { idx =>
      val from = Board.posFromSquareIndex(idx)
      Array.tabulate(sliderDirections.length) { dirIdx =>
        val (dc, dr) = sliderDirections(dirIdx)
        var current = from + (dc, dr)
        var mask = 0L
        while current.isValid do
          mask |= Board.squareMask(current)
          current = current + (dc, dr)
        mask
      }
    }

  private def sliderAttackMask(fromIdx: Int, occupied: Long, directionIndices: Array[Int]): Long =
    var attacks = 0L
    var i = 0
    while i < directionIndices.length do
      val dirIdx = directionIndices(i)
      val ray = sliderRays(fromIdx)(dirIdx)
      val blockers = ray & occupied
      if blockers == 0L then
        attacks |= ray
      else
        val blockerIdx =
          if sliderDirectionAscending(dirIdx) then java.lang.Long.numberOfTrailingZeros(blockers)
          else 63 - java.lang.Long.numberOfLeadingZeros(blockers)
        attacks |= ray ^ sliderRays(blockerIdx)(dirIdx)
      i += 1
    attacks
