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

  def toFenPlacement: String =
    val ranks = scala.collection.mutable.ListBuffer.empty[String]
    var row = 7
    while row >= 0 do
      val parts = scala.collection.mutable.ListBuffer.empty[String]
      var emptyCount = 0
      var col = 0
      while col < 8 do
        pieces.get(Pos(col, row)) match
          case Some(piece) =>
            if emptyCount > 0 then
              parts += emptyCount.toString
              emptyCount = 0
            parts += piece.letter
          case None =>
            emptyCount += 1
        col += 1
      if emptyCount > 0 then parts += emptyCount.toString
      ranks += parts.mkString
      row -= 1
    ranks.mkString("/")

object Board:
  /** Standard starting position */
  def initial: Board =
    val backRank = List(
      PieceType.Rook,
      PieceType.Knight,
      PieceType.Bishop,
      PieceType.Queen,
      PieceType.King,
      PieceType.Bishop,
      PieceType.Knight,
      PieceType.Rook
    )
    var allPieces = Map.empty[Pos, Piece]
    var col = 0
    while col < 8 do
      allPieces += Pos(col, 0) -> Piece(Color.White, backRank(col))
      allPieces += Pos(col, 1) -> Piece(Color.White, PieceType.Pawn)
      allPieces += Pos(col, 7) -> Piece(Color.Black, backRank(col))
      allPieces += Pos(col, 6) -> Piece(Color.Black, PieceType.Pawn)
      col += 1
    Board(allPieces)

  def empty: Board = Board(Map.empty)

  def fromFenPlacement(placement: String): Either[String, Board] =
    val ranks = placement.split("/")
    if ranks.length != 8 then Left("FEN placement must contain 8 ranks")
    else
      var pieces = Map.empty[Pos, Piece]
      var rankIndex = 0
      while rankIndex < ranks.length do
        parseRank(ranks(rankIndex), 7 - rankIndex) match
          case Left(error) => return Left(error)
          case Right(parsedRank) =>
            pieces ++= parsedRank
        rankIndex += 1
      Right(Board(pieces))

  private def parseRank(rank: String, row: Int): Either[String, Map[Pos, Piece]] =
    var col = 0
    var pieces = Map.empty[Pos, Piece]
    var index = 0
    while index < rank.length do
      val char = rank(index)
      if char.isDigit then
        val emptySquares = char.asDigit
        if emptySquares < 1 || emptySquares > 8 then
          return Left(s"Invalid empty-square count '$char' in FEN")
        if col + emptySquares > 8 then
          return Left(s"Rank '$rank' exceeds 8 files in FEN")
        col += emptySquares
      else
        Piece.fromFenChar(char) match
          case None =>
            return Left(s"Invalid piece '$char' in FEN")
          case Some(piece) =>
            if col >= 8 then
              return Left(s"Rank '$rank' exceeds 8 files in FEN")
            pieces += Pos(col, row) -> piece
            col += 1
      index += 1
    if col == 8 then Right(pieces)
    else Left(s"Rank '$rank' must describe exactly 8 files in FEN")

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

  def toFen: String =
    val enPassant = enPassantTarget.map(_.toAlgebraic).getOrElse("-")
    s"${board.toFenPlacement} ${GameState.colorToFen(activeColor)} ${castlingRights.toFen} $enPassant $halfMoveClock $fullMoveNumber"

object GameState:
  val initialFen: String = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  def initial: GameState = GameState(
    board           = Board.initial,
    activeColor     = Color.White,
    castlingRights  = CastlingRights(),
    enPassantTarget = None,
    halfMoveClock   = 0,
    fullMoveNumber  = 1,
    history         = Nil
  )

  def fromFen(fen: String): Either[String, GameState] =
    fen.trim.split("\\s+") match
      case Array(placement, active, castling, enPassant, halfMove, fullMove) =>
        Board.fromFenPlacement(placement) match
          case Left(error) => Left(error)
          case Right(board) =>
            colorFromFen(active) match
              case Left(error) => Left(error)
              case Right(activeColor) =>
                CastlingRights.fromFen(castling) match
                  case None => Left("Invalid castling rights in FEN")
                  case Some(castlingRights) =>
                    parseEnPassant(enPassant) match
                      case Left(error) => Left(error)
                      case Right(enPassantTarget) =>
                        parseNonNegativeInt(halfMove, "halfmove clock") match
                          case Left(error) => Left(error)
                          case Right(halfMoveClock) =>
                            parsePositiveInt(fullMove, "fullmove number") match
                              case Left(error) => Left(error)
                              case Right(fullMoveNumber) =>
                                Right(GameState(
                                  board = board,
                                  activeColor = activeColor,
                                  castlingRights = castlingRights,
                                  enPassantTarget = enPassantTarget,
                                  halfMoveClock = halfMoveClock,
                                  fullMoveNumber = fullMoveNumber
                                ))
      case _ => Left("FEN must contain 6 space-separated fields")

  private[model] def colorToFen(color: Color): String = color match
    case Color.White => "w"
    case Color.Black => "b"

  private def colorFromFen(value: String): Either[String, Color] = value match
    case "w" => Right(Color.White)
    case "b" => Right(Color.Black)
    case _   => Left("Invalid active color in FEN")

  private def parseEnPassant(value: String): Either[String, Option[Pos]] =
    if value == "-" then Right(None)
    else
      Pos.fromAlgebraic(value) match
        case Some(pos) if pos.row == 2 || pos.row == 5 => Right(Some(pos))
        case Some(_) => Left("Invalid en passant target rank in FEN")
        case None    => Left("Invalid en passant target square in FEN")

  private def parseNonNegativeInt(value: String, label: String): Either[String, Int] =
    value.toIntOption match
      case Some(number) if number >= 0 => Right(number)
      case _                           => Left(s"Invalid $label in FEN")

  private def parsePositiveInt(value: String, label: String): Either[String, Int] =
    value.toIntOption match
      case Some(number) if number > 0 => Right(number)
      case _                          => Left(s"Invalid $label in FEN")
