package chess.model

// ─── MoveGenerator ───────────────────────────────────────────────────────────

object MoveGenerator:

  // ── Public API ─────────────────────────────────────────────────────────────

  /** All fully legal moves for the active player */
  def legalMoves(state: GameState): List[Move] =
    pseudoLegalMoves(state).filter(move => !leavesKingInCheck(state, move))

  /** Legal moves for a specific piece on `from` */
  def legalMovesFrom(state: GameState, from: Pos): List[Move] =
    legalMoves(state).filter(_.from == from)

  /** True if the king of `color` is currently attacked */
  def isInCheck(state: GameState, color: Color): Boolean =
    state.board.findKing(color) match
      case None      => false   // shouldn't happen in a real game
      case Some(pos) => isAttackedBy(state.board, pos, color.opposite)

  // ── Pseudo-legal move generation ───────────────────────────────────────────

  private def pseudoLegalMoves(state: GameState): List[Move] =
    state.board.allPiecesOf(state.activeColor).flatMap { case (pos, piece) =>
      piece.pieceType match
        case PieceType.Pawn   => pawnMoves(state, pos, piece.color)
        case PieceType.Knight => knightMoves(state, pos, piece.color)
        case PieceType.Bishop => slidingMoves(state, pos, piece.color, bishopDirs)
        case PieceType.Rook   => slidingMoves(state, pos, piece.color, rookDirs)
        case PieceType.Queen  => slidingMoves(state, pos, piece.color, queenDirs)
        case PieceType.King   => kingMoves(state, pos, piece.color)
    }

  // ── Directions ─────────────────────────────────────────────────────────────

  private val bishopDirs = List((1,1),(1,-1),(-1,1),(-1,-1))
  private val rookDirs   = List((1,0),(-1,0),(0,1),(0,-1))
  private val queenDirs  = bishopDirs ++ rookDirs

  // ── Piece-specific generators ──────────────────────────────────────────────

  private def pawnMoves(state: GameState, pos: Pos, color: Color): List[Move] =
    val dir       = if color == Color.White then 1 else -1
    val startRow  = if color == Color.White then 1 else 6
    val promRow   = if color == Color.White then 7 else 0
    val board     = state.board

    def withPromotion(to: Pos): List[Move] =
      if to.row == promRow then
        List(PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight)
          .map(pt => Move(pos, to, Some(pt)))
      else
        List(Move(pos, to))

    // single step
    val oneStep = pos + (0, dir)
    val single  = if oneStep.isValid && board.isEmpty(oneStep) then withPromotion(oneStep) else Nil

    // double step from starting row
    val twoStep = pos + (0, 2 * dir)
    val double  =
      if pos.row == startRow && board.isEmpty(oneStep) && board.isEmpty(twoStep)
      then List(Move(pos, twoStep))
      else Nil

    // diagonal captures (including en passant)
    val captures = List(-1, 1).flatMap { dc =>
      val target = pos + (dc, dir)
      if !target.isValid then Nil
      else if board.isOccupiedBy(target, color.opposite) then withPromotion(target)
      else if state.enPassantTarget.contains(target)      then List(Move(pos, target))
      else Nil
    }

    single ++ double ++ captures

  private def knightMoves(state: GameState, pos: Pos, color: Color): List[Move] =
    val deltas = List((2,1),(2,-1),(-2,1),(-2,-1),(1,2),(1,-2),(-1,2),(-1,-2))
    deltas.flatMap { (dc, dr) =>
      val to = pos + (dc, dr)
      Option.when(to.isValid && !state.board.isOccupiedBy(to, color))(Move(pos, to))
    }

  private def slidingMoves(
    state: GameState, pos: Pos, color: Color, dirs: List[(Int, Int)]
  ): List[Move] =
    dirs.flatMap { (dc, dr) =>
      LazyList
        .iterate(pos + (dc, dr))(p => p + (dc, dr))
        .takeWhile(_.isValid)
        .foldLeft((List.empty[Move], false)) { case ((acc, blocked), to) =>
          if blocked then (acc, true)
          else if state.board.isOccupiedBy(to, color) then (acc, true)
          else if state.board.isOccupiedBy(to, color.opposite) then (acc :+ Move(pos, to), true)
          else (acc :+ Move(pos, to), false)
        }
        ._1
    }

  private def kingMoves(state: GameState, pos: Pos, color: Color): List[Move] =
    val normal = queenDirs.flatMap { (dc, dr) =>
      val to = pos + (dc, dr)
      Option.when(to.isValid && !state.board.isOccupiedBy(to, color))(Move(pos, to))
    }
    normal ++ castlingMoves(state, pos, color)

  // ── Castling ───────────────────────────────────────────────────────────────

  private def castlingMoves(state: GameState, kingPos: Pos, color: Color): List[Move] =
    val cr    = state.castlingRights
    val board = state.board
    val row   = if color == Color.White then 0 else 7

    // king must not currently be in check
    if isAttackedBy(board, kingPos, color.opposite) then return Nil

    def canCastle(
      kingSide: Boolean,
      hasRight: Boolean
    ): Option[Move] =
      if !hasRight then None
      else
        val (rookCol, emptyRange, transitCols) =
          if kingSide then (7, 5 to 6, List(5, 6))
          else             (0, 1 to 3, List(3, 4))

        val rookPos = Pos(rookCol, row)
        val isRookPresent = board.get(rookPos).contains(Piece(color, PieceType.Rook))
        val pathClear     = emptyRange.forall(c => board.isEmpty(Pos(c, row)))
        val noTransitCheck = transitCols.forall { c =>
          !isAttackedBy(board, Pos(c, row), color.opposite)
        }
        if isRookPresent && pathClear && noTransitCheck then
          val toCol = if kingSide then 6 else 2
          Some(Move(kingPos, Pos(toCol, row)))
        else None

    List(
      canCastle(kingSide = true,  hasRight = if color == Color.White then cr.whiteKingSide  else cr.blackKingSide),
      canCastle(kingSide = false, hasRight = if color == Color.White then cr.whiteQueenSide else cr.blackQueenSide)
    ).flatten

  // ── Attack detection ───────────────────────────────────────────────────────

  /** Is `pos` attacked by any piece of `attacker` on `board`? */
  def isAttackedBy(board: Board, pos: Pos, attacker: Color): Boolean =
    val defender = attacker.opposite

    // pawn attacks
    val pawnDir = if attacker == Color.White then 1 else -1
    val pawnAttack = List(-1, 1).exists { dc =>
      board.get(pos + (dc, -pawnDir)).contains(Piece(attacker, PieceType.Pawn))
    }
    if pawnAttack then return true

    // knight attacks
    val knightAttack = List((2,1),(2,-1),(-2,1),(-2,-1),(1,2),(1,-2),(-1,2),(-1,-2)).exists { (dc, dr) =>
      board.get(pos + (dc, dr)).contains(Piece(attacker, PieceType.Knight))
    }
    if knightAttack then return true

    // king attacks
    val kingAttack = queenDirs.exists { (dc, dr) =>
      board.get(pos + (dc, dr)).contains(Piece(attacker, PieceType.King))
    }
    if kingAttack then return true

    // sliding attacks
    def slidingAttack(dirs: List[(Int, Int)], types: Set[PieceType]): Boolean =
      dirs.exists { (dc, dr) =>
        LazyList
          .iterate(pos + (dc, dr))(_ + (dc, dr))
          .takeWhile(_.isValid)
          .map(board.get)
          .collectFirst { case Some(p) => p } match
            case Some(Piece(c, pt)) if c == attacker && types.contains(pt) => true
            case _                                                        => false
      }

    slidingAttack(bishopDirs, Set(PieceType.Bishop, PieceType.Queen)) ||
    slidingAttack(rookDirs,   Set(PieceType.Rook,   PieceType.Queen))

  // ── Legality filter ────────────────────────────────────────────────────────

  /** Apply move to board only (no state update) and check if own king is attacked */
  private def leavesKingInCheck(state: GameState, move: Move): Boolean =
    val nextBoard = applyMoveToBoard(state, move)
    nextBoard.findKing(state.activeColor) match
      case None      => false
      case Some(pos) => isAttackedBy(nextBoard, pos, state.activeColor.opposite)

  /** Minimal board-only application used for check detection */
  private def applyMoveToBoard(state: GameState, move: Move): Board =
    val board = state.board
    board.get(move.from) match
      case None => board
      case Some(piece) =>
        // en passant capture
        val boardAfterEP =
          if piece.pieceType == PieceType.Pawn && state.enPassantTarget.contains(move.to) then
            val dir = if piece.color == Color.White then -1 else 1
            board.remove(move.to + (0, dir))
          else board

        // castling: move rook
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

        // promotion
        val finalPiece = move.promotion match
          case Some(pt) => Piece(piece.color, pt)
          case None     => piece

        boardAfterCastle.remove(move.from).put(move.to, finalPiece)
