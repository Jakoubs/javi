package chess.model

object MoveGenerator:

  private def bishopDirections: Array[(Int, Int)] = Array((1, 1), (1, -1), (-1, 1), (-1, -1))
  private def rookDirections: Array[(Int, Int)] = Array((1, 0), (-1, 0), (0, 1), (0, -1))
  private def knightDeltas: Array[(Int, Int)] = Array((2, 1), (2, -1), (-2, 1), (-2, -1), (1, 2), (1, -2), (-1, 2), (-1, -2))
  private def kingDirections: Array[(Int, Int)] = Array((1, 1), (1, -1), (-1, 1), (-1, -1), (1, 0), (-1, 0), (0, 1), (0, -1))

  def legalMoves(state: GameState): List[Move] =
    pseudoLegalMoves(state).filter(move => !leavesKingInCheck(state, move))

  def legalMovesFrom(state: GameState, from: Pos): List[Move] =
    legalMoves(state).filter(_.from == from)

  def isInCheck(state: GameState, color: Color): Boolean =
    state.board.findKing(color) match
      case None      => false
      case Some(pos) => isAttackedBy(state.board, pos, color.opposite)

  private def pseudoLegalMoves(state: GameState): List[Move] =
    val pieces = state.board.allPiecesOf(state.activeColor)
    val moves = scala.collection.mutable.ListBuffer.empty[Move]
    var index = 0
    while index < pieces.length do
      val (pos, piece) = pieces(index)
      piece.pieceType match
        case PieceType.Pawn =>
          moves ++= pawnMoves(state, pos, piece.color)
        case PieceType.Knight =>
          moves ++= knightMoves(state, pos, piece.color)
        case PieceType.Bishop =>
          moves ++= slidingMoves(state, pos, piece.color, bishopDirections)
        case PieceType.Rook =>
          moves ++= slidingMoves(state, pos, piece.color, rookDirections)
        case PieceType.Queen =>
          moves ++= queenMoves(state, pos, piece.color)
        case PieceType.King =>
          moves ++= kingMoves(state, pos, piece.color)
      index += 1
    moves.toList

  private def pawnMoves(state: GameState, pos: Pos, color: Color): List[Move] =
    val dir = if color == Color.White then 1 else -1
    val startRow = if color == Color.White then 1 else 6
    val promRow = if color == Color.White then 7 else 0
    val board = state.board
    val moves = scala.collection.mutable.ListBuffer.empty[Move]

    def addMoves(to: Pos): Unit =
      if to.row == promRow then
        moves += Move(pos, to, Some(PieceType.Queen))
        moves += Move(pos, to, Some(PieceType.Rook))
        moves += Move(pos, to, Some(PieceType.Bishop))
        moves += Move(pos, to, Some(PieceType.Knight))
      else
        moves += Move(pos, to)

    val oneStep = pos + (0, dir)
    if oneStep.isValid && board.isEmpty(oneStep) then
      addMoves(oneStep)

    val twoStep = pos + (0, 2 * dir)
    if pos.row == startRow && board.isEmpty(oneStep) && board.isEmpty(twoStep) then
      moves += Move(pos, twoStep)

    val leftTarget = pos + (-1, dir)
    if leftTarget.isValid then
      if board.isOccupiedBy(leftTarget, color.opposite) then addMoves(leftTarget)
      else if state.enPassantTarget.contains(leftTarget) then moves += Move(pos, leftTarget)

    val rightTarget = pos + (1, dir)
    if rightTarget.isValid then
      if board.isOccupiedBy(rightTarget, color.opposite) then addMoves(rightTarget)
      else if state.enPassantTarget.contains(rightTarget) then moves += Move(pos, rightTarget)

    moves.toList

  private def knightMoves(state: GameState, pos: Pos, color: Color): List[Move] =
    val moves = scala.collection.mutable.ListBuffer.empty[Move]
    val deltas = knightDeltas
    var index = 0
    while index < deltas.length do
      val (dc, dr) = deltas(index)
      val to = pos + (dc, dr)
      if to.isValid && !state.board.isOccupiedBy(to, color) then
        moves += Move(pos, to)
      index += 1
    moves.toList

  private def slidingMoves(
    state: GameState,
    pos: Pos,
    color: Color,
    directions: Array[(Int, Int)]
  ): List[Move] =
    val moves = scala.collection.mutable.ListBuffer.empty[Move]
    var dirIndex = 0
    while dirIndex < directions.length do
      val (dc, dr) = directions(dirIndex)
      var current = pos + (dc, dr)
      var blocked = false
      while current.isValid && !blocked do
        if state.board.isOccupiedBy(current, color) then
          blocked = true
        else if state.board.isOccupiedBy(current, color.opposite) then
          moves += Move(pos, current)
          blocked = true
        else
          moves += Move(pos, current)
          current = current + (dc, dr)
      dirIndex += 1
    moves.toList

  private def queenMoves(state: GameState, pos: Pos, color: Color): List[Move] =
    slidingMoves(state, pos, color, Array((1, 1), (1, -1), (-1, 1), (-1, -1), (1, 0), (-1, 0), (0, 1), (0, -1)))

  private def kingMoves(state: GameState, pos: Pos, color: Color): List[Move] =
    val moves = scala.collection.mutable.ListBuffer.empty[Move]
    val directions = kingDirections
    var index = 0
    while index < directions.length do
      val (dc, dr) = directions(index)
      val to = pos + (dc, dr)
      if to.isValid && !state.board.isOccupiedBy(to, color) then
        moves += Move(pos, to)
      index += 1
    moves ++= castlingMoves(state, pos, color)
    moves.toList

  // $COVERAGE-OFF$
  private def castlingMoves(state: GameState, kingPos: Pos, color: Color): List[Move] =
    val moves = scala.collection.mutable.ListBuffer.empty[Move]
    val board = state.board
    val rights = state.castlingRights
    val row = if color == Color.White then 0 else 7

    if isAttackedBy(board, kingPos, color.opposite) then
      return Nil

    val kingSideAllowed = if color == Color.White then rights.whiteKingSide else rights.blackKingSide
    if kingSideAllowed &&
      board.get(Pos(7, row)).contains(Piece(color, PieceType.Rook)) &&
      board.isEmpty(Pos(5, row)) &&
      board.isEmpty(Pos(6, row)) &&
      !isAttackedBy(board, Pos(5, row), color.opposite) &&
      !isAttackedBy(board, Pos(6, row), color.opposite)
    then
      moves += Move(kingPos, Pos(6, row))

    val queenSideAllowed = if color == Color.White then rights.whiteQueenSide else rights.blackQueenSide
    if queenSideAllowed &&
      board.get(Pos(0, row)).contains(Piece(color, PieceType.Rook)) &&
      board.isEmpty(Pos(1, row)) &&
      board.isEmpty(Pos(2, row)) &&
      board.isEmpty(Pos(3, row)) &&
      !isAttackedBy(board, Pos(3, row), color.opposite) &&
      !isAttackedBy(board, Pos(4, row), color.opposite)
    then
      moves += Move(kingPos, Pos(2, row))

    moves.toList

  def isAttackedBy(board: Board, pos: Pos, attacker: Color): Boolean =
    val pawnDir = if attacker == Color.White then 1 else -1
    if board.get(pos + (-1, -pawnDir)).contains(Piece(attacker, PieceType.Pawn)) then return true
    if board.get(pos + (1, -pawnDir)).contains(Piece(attacker, PieceType.Pawn)) then return true

    val deltas = knightDeltas
    var index = 0
    while index < deltas.length do
      val (dc, dr) = deltas(index)
      if board.get(pos + (dc, dr)).contains(Piece(attacker, PieceType.Knight)) then return true
      index += 1

    val kings = kingDirections
    index = 0
    while index < kings.length do
      val (dc, dr) = kings(index)
      if board.get(pos + (dc, dr)).contains(Piece(attacker, PieceType.King)) then return true
      index += 1

    slidingAttack(board, pos, attacker, bishopDirections, PieceType.Bishop) ||
    slidingAttack(board, pos, attacker, rookDirections, PieceType.Rook)

  private def slidingAttack(
    board: Board,
    pos: Pos,
    attacker: Color,
    directions: Array[(Int, Int)],
    primary: PieceType
  ): Boolean =
    var dirIndex = 0
    while dirIndex < directions.length do
      val (dc, dr) = directions(dirIndex)
      var current = pos + (dc, dr)
      var blocked = false
      while current.isValid && !blocked do
        board.get(current) match
          case None =>
            current = current + (dc, dr)
          case Some(Piece(c, pt)) if c == attacker && (pt == primary || pt == PieceType.Queen) =>
            return true
          case _ =>
            blocked = true
      dirIndex += 1
    false

  private def leavesKingInCheck(state: GameState, move: Move): Boolean =
    val nextBoard = applyMoveToBoard(state, move)
    nextBoard.findKing(state.activeColor).exists(pos => isAttackedBy(nextBoard, pos, state.activeColor.opposite))

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
