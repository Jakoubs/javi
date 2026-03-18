package chess.model

// ─── Board ───────────────────────────────────────────────────────────────────

/** Immutable 8×8 board represented as a Map from Pos to Piece */
case class Board(pieces: Map[Pos, Piece]):

  def get(pos: Pos): Option[Piece] = pieces.get(pos)

  def isOccupied(pos: Pos): Boolean = pieces.contains(pos)

  def isOccupiedBy(pos: Pos, color: Color): Boolean =
    pieces.get(pos).exists(_.color == color)

  def isEmpty(pos: Pos): Boolean = !pieces.contains(pos)

  /** Place a piece (overwrites) */
  def put(pos: Pos, piece: Piece): Board = copy(pieces = pieces + (pos -> piece))

  /** Remove a piece */
  def remove(pos: Pos): Board = copy(pieces = pieces - pos)

  /** Move without any special logic */
  def movePiece(from: Pos, to: Pos): Board =
    pieces.get(from) match
      case None        => this
      case Some(piece) => copy(pieces = (pieces - from) + (to -> piece))

  def findKing(color: Color): Option[Pos] =
    pieces.collectFirst { case (pos, Piece(c, PieceType.King)) if c == color => pos }

  def allPiecesOf(color: Color): List[(Pos, Piece)] =
    pieces.toList.filter(_._2.color == color)

object Board:
  /** Standard starting position */
  def initial: Board =
    val backRank: Color => List[PieceType] = _ =>
      List(PieceType.Rook, PieceType.Knight, PieceType.Bishop, PieceType.Queen,
           PieceType.King, PieceType.Bishop, PieceType.Knight, PieceType.Rook)

    val whitePieces =
      (0 to 7).map(c => Pos(c, 0) -> Piece(Color.White, backRank(Color.White)(c))).toMap ++
      (0 to 7).map(c => Pos(c, 1) -> Piece(Color.White, PieceType.Pawn)).toMap

    val blackPieces =
      (0 to 7).map(c => Pos(c, 7) -> Piece(Color.Black, backRank(Color.Black)(c))).toMap ++
      (0 to 7).map(c => Pos(c, 6) -> Piece(Color.Black, PieceType.Pawn)).toMap

    Board(whitePieces ++ blackPieces)

  def empty: Board = Board(Map.empty)

// ─── GameState ───────────────────────────────────────────────────────────────

/**
 * Complete, immutable game state.
 *
 * @param board          current board
 * @param activeColor    whose turn it is
 * @param castlingRights remaining castling rights
 * @param enPassantTarget square a pawn may capture en-passant, if any
 * @param halfMoveClock  half-moves since last capture or pawn move (for 50-move rule)
 * @param fullMoveNumber increments after Black's move
 * @param history        previous states for threefold-repetition detection
 */
case class GameState(
  board:           Board,
  activeColor:     Color,
  castlingRights:  CastlingRights,
  enPassantTarget: Option[Pos],
  halfMoveClock:   Int,
  fullMoveNumber:  Int,
  history:         List[GameState] = Nil   // oldest first, current excluded
):
  def withHistory: GameState = copy(history = history :+ this)

object GameState:
  def initial: GameState = GameState(
    board           = Board.initial,
    activeColor     = Color.White,
    castlingRights  = CastlingRights(),
    enPassantTarget = None,
    halfMoveClock   = 0,
    fullMoveNumber  = 1,
    history         = Nil
  )
