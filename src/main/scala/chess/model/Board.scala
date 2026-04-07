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

  /** Public convenience method for parsing a FEN placement string */
  def fromFEN(fen: String): Either[String, Board] = fromFenPlacement(fen)

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
 */
sealed trait GameState:
  def board:           Board
  def activeColor:     Color
  def castlingRights:  CastlingRights
  def enPassantTarget: Option[Pos]
  def halfMoveClock:   Int
  def fullMoveNumber:  Int
  def history:         List[GameState]

  def withHistory: GameState
  
  def toFen: String =
    val enPassant = enPassantTarget.map(_.toAlgebraic).getOrElse("-")
    s"${board.toFenPlacement} ${GameState.colorToFen(activeColor)} ${castlingRights.toFen} $enPassant $halfMoveClock $fullMoveNumber"

extension (s: GameState)
  def copy(
    board:           Board           = s.board,
    castlingRights:  CastlingRights  = s.castlingRights,
    enPassantTarget: Option[Pos]     = s.enPassantTarget,
    halfMoveClock:   Int             = s.halfMoveClock,
    fullMoveNumber:  Int             = s.fullMoveNumber,
    history:         List[GameState] = s.history
  ): GameState = s match
    case w: WhiteToMove => w.copy(board, castlingRights, enPassantTarget, halfMoveClock, fullMoveNumber, history)
    case b: BlackToMove => b.copy(board, castlingRights, enPassantTarget, halfMoveClock, fullMoveNumber, history)

  def withActiveColor(color: Color): GameState = (s, color) match
    case (w: WhiteToMove, Color.White) => w
    case (b: BlackToMove, Color.Black) => b
    case (w: WhiteToMove, Color.Black) => BlackToMove(w.board, w.castlingRights, w.enPassantTarget, w.halfMoveClock, w.fullMoveNumber, w.history)
    case (b: BlackToMove, Color.White) => WhiteToMove(b.board, b.castlingRights, b.enPassantTarget, b.halfMoveClock, b.fullMoveNumber, b.history)

  /** Returns all pieces of the given color that have been captured (missing from board compared to starting position) */
  def capturedPieces(color: Color): List[PieceType] =
    val startingCounts = Map(
      PieceType.Pawn -> 8, PieceType.Knight -> 2, PieceType.Bishop -> 2,
      PieceType.Rook -> 2, PieceType.Queen -> 1
    )
    val currentPieces = s.board.allPiecesOf(color).map(_._2.pieceType)
    val currentCounts = currentPieces.groupBy(identity).view.mapValues(_.size).toMap
    
    startingCounts.flatMap { (pt, initialCount) =>
      val currentCount = currentCounts.getOrElse(pt, 0)
      List.fill(Math.max(0, initialCount - currentCount))(pt)
    }.toList

case class WhiteToMove(
  board:           Board,
  castlingRights:  CastlingRights,
  enPassantTarget: Option[Pos],
  halfMoveClock:   Int,
  fullMoveNumber:  Int,
  history:         List[GameState] = Nil
) extends GameState:
  val activeColor: Color = Color.White
  def withHistory: GameState = copy(history = history :+ this)

case class BlackToMove(
  board:           Board,
  castlingRights:  CastlingRights,
  enPassantTarget: Option[Pos],
  halfMoveClock:   Int,
  fullMoveNumber:  Int,
  history:         List[GameState] = Nil
) extends GameState:
  val activeColor: Color = Color.Black
  def withHistory: GameState = copy(history = history :+ this)

object GameState:
  val initialFen: String = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  def initial: GameState = WhiteToMove(
    board           = Board.initial,
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
                                Right(
                                  if activeColor == Color.White then
                                    WhiteToMove(board, castlingRights, enPassantTarget, halfMoveClock, fullMoveNumber)
                                  else
                                    BlackToMove(board, castlingRights, enPassantTarget, halfMoveClock, fullMoveNumber)
                                )
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
