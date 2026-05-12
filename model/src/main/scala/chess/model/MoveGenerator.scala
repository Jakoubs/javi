package chess.model

object MoveGenerator:

  private val bishopDirections: Array[(Int, Int)] = Array((1, 1), (1, -1), (-1, 1), (-1, -1))
  private val rookDirections: Array[(Int, Int)] = Array((1, 0), (-1, 0), (0, 1), (0, -1))
  private val queenDirections: Array[(Int, Int)] = bishopDirections ++ rookDirections
  private val knightDeltas: Array[(Int, Int)] = Array((2, 1), (2, -1), (-2, 1), (-2, -1), (1, 2), (1, -2), (-1, 2), (-1, -2))
  private val kingDirections: Array[(Int, Int)] = Array((1, 1), (1, -1), (-1, 1), (-1, -1), (1, 0), (-1, 0), (0, 1), (0, -1))

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
      case None      => false
      case Some(pos) => isAttackedBy(state.board, pos, color.opposite)

  private def pseudoLegalMoves(state: GameState): List[Move] =
    val moves = scala.collection.mutable.ArrayBuffer.empty[Move]
    state.board.pieces.foreach { case (pos, piece) =>
      if piece.color == state.activeColor then
        addPseudoMovesForPiece(moves, state, pos, piece)
    }
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
        knightMoves(out, state, pos, piece.color)
      case PieceType.Bishop =>
        slidingMoves(out, state, pos, piece.color, bishopDirections)
      case PieceType.Rook =>
        slidingMoves(out, state, pos, piece.color, rookDirections)
      case PieceType.Queen =>
        queenMoves(out, state, pos, piece.color)
      case PieceType.King =>
        kingMoves(out, state, pos, piece.color)

  private def pawnMoves(out: scala.collection.mutable.ArrayBuffer[Move], state: GameState, pos: Pos, color: Color): Unit =
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

  private def knightMoves(out: scala.collection.mutable.ArrayBuffer[Move], state: GameState, pos: Pos, color: Color): Unit =
    val deltas = knightDeltas
    var index = 0
    while index < deltas.length do
      val (dc, dr) = deltas(index)
      val to = pos + (dc, dr)
      if to.isValid && !state.board.isOccupiedBy(to, color) then
        out += Move(pos, to)
      index += 1

  private def slidingMoves(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    pos: Pos,
    color: Color,
    directions: Array[(Int, Int)]
  ): Unit =
    var dirIndex = 0
    while dirIndex < directions.length do
      val (dc, dr) = directions(dirIndex)
      var current = pos + (dc, dr)
      var blocked = false
      while current.isValid && !blocked do
        if state.board.isOccupiedBy(current, color) then
          blocked = true
        else if state.board.isOccupiedBy(current, color.opposite) then
          out += Move(pos, current)
          blocked = true
        else
          out += Move(pos, current)
          current = current + (dc, dr)
      dirIndex += 1

  private def queenMoves(out: scala.collection.mutable.ArrayBuffer[Move], state: GameState, pos: Pos, color: Color): Unit =
    slidingMoves(out, state, pos, color, queenDirections)

  private def kingMoves(out: scala.collection.mutable.ArrayBuffer[Move], state: GameState, pos: Pos, color: Color): Unit =
    val directions = kingDirections
    var index = 0
    while index < directions.length do
      val (dc, dr) = directions(index)
      val to = pos + (dc, dr)
      if to.isValid && !state.board.isOccupiedBy(to, color) then
        out += Move(pos, to)
      index += 1
    addCastlingMoves(out, state, pos, color)

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

  def isAttackedBy(board: Board, pos: Pos, attacker: Color): Boolean = {
    val pawnDir = if attacker == Color.White then 1 else -1
    val pawnAttacked = 
      board.get(pos + (-1, -pawnDir)).contains(Piece(attacker, PieceType.Pawn)) ||
      board.get(pos + (1, -pawnDir)).contains(Piece(attacker, PieceType.Pawn))
    
    lazy val knightAttacked = {
      val deltas = knightDeltas
      var kIndex = 0
      var found = false
      while kIndex < deltas.length && !found do
        val (dc, dr) = deltas(kIndex)
        if board.get(pos + (dc, dr)).contains(Piece(attacker, PieceType.Knight)) then
          found = true
        kIndex += 1
      found
    }

    lazy val kingAttacked = {
      val kings = kingDirections
      var kgIndex = 0
      var found = false
      while kgIndex < kings.length && !found do
        val (dc, dr) = kings(kgIndex)
        if board.get(pos + (dc, dr)).contains(Piece(attacker, PieceType.King)) then
          found = true
        kgIndex += 1
      found
    }

    pawnAttacked || knightAttacked || kingAttacked ||
    slidingAttack(board, pos, attacker, bishopDirections, PieceType.Bishop) ||
    slidingAttack(board, pos, attacker, rookDirections, PieceType.Rook)
  }

  private def slidingAttack(
    board: Board,
    pos: Pos,
    attacker: Color,
    directions: Array[(Int, Int)],
    primary: PieceType
  ): Boolean = {
    var found = false
    var dirIndex = 0
    while dirIndex < directions.length && !found do
      val (dc, dr) = directions(dirIndex)
      var current = pos + (dc, dr)
      var blocked = false
      while current.isValid && !blocked && !found do
        board.get(current) match
          case None =>
            current = current + (dc, dr)
          case Some(Piece(c, pt)) if c == attacker && (pt == primary || pt == PieceType.Queen) =>
            found = true
          case _ =>
            blocked = true
      dirIndex += 1
    found
  }

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
      case None     => piece

    boardAfterCastle.remove(move.from).put(move.to, finalPiece)
  // $COVERAGE-ON$
