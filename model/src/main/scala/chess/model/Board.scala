package chess.model

import java.util.SplittableRandom

/** Immutable 8x8 board represented internally as bitboards. */
final case class Board private (
  private val whitePawnsBb: Long,
  private val whiteKnightsBb: Long,
  private val whiteBishopsBb: Long,
  private val whiteRooksBb: Long,
  private val whiteQueensBb: Long,
  private val whiteKingBb: Long,
  private val blackPawnsBb: Long,
  private val blackKnightsBb: Long,
  private val blackBishopsBb: Long,
  private val blackRooksBb: Long,
  private val blackQueensBb: Long,
  private val blackKingBb: Long
):
  import Board.*

  lazy val pieces: Map[Pos, Piece] =
    val builder = Map.newBuilder[Pos, Piece]
    foreachPiece { (pos, piece) =>
      builder += (pos -> piece)
    }
    builder.result()

  def pieceCount: Int =
    java.lang.Long.bitCount(occupiedBb)

  private[chess] def occupiedMask: Long =
    occupiedBb

  private[chess] def colorMask(color: Color): Long =
    occupancyOf(color)

  private[chess] def bitboardOf(color: Color, pieceType: PieceType): Long =
    (color, pieceType) match
      case (Color.White, PieceType.Pawn)   => whitePawnsBb
      case (Color.White, PieceType.Knight) => whiteKnightsBb
      case (Color.White, PieceType.Bishop) => whiteBishopsBb
      case (Color.White, PieceType.Rook)   => whiteRooksBb
      case (Color.White, PieceType.Queen)  => whiteQueensBb
      case (Color.White, PieceType.King)   => whiteKingBb
      case (Color.Black, PieceType.Pawn)   => blackPawnsBb
      case (Color.Black, PieceType.Knight) => blackKnightsBb
      case (Color.Black, PieceType.Bishop) => blackBishopsBb
      case (Color.Black, PieceType.Rook)   => blackRooksBb
      case (Color.Black, PieceType.Queen)  => blackQueensBb
      case (Color.Black, PieceType.King)   => blackKingBb

  def get(pos: Pos): Option[Piece] =
    if !pos.isValid then None
    else pieceAtMask(squareMask(pos))

  private[chess] def pieceAtOrNull(pos: Pos): Piece | Null =
    if !pos.isValid then null
    else pieceAtMaskOrNull(squareMask(pos))

  def isOccupied(pos: Pos): Boolean =
    pos.isValid && ((occupiedBb & squareMask(pos)) != 0L)

  def isOccupiedBy(pos: Pos, color: Color): Boolean =
    pos.isValid && ((occupancyOf(color) & squareMask(pos)) != 0L)

  def isEmpty(pos: Pos): Boolean = !isOccupied(pos)

  /** Place a piece (overwrites). */
  def put(pos: Pos, piece: Piece): Board =
    val mask = squareMask(pos)
    clearMask(mask).addPiece(piece, mask)

  /** Remove a piece. */
  def remove(pos: Pos): Board =
    if !pos.isValid then this else clearMask(squareMask(pos))

  /** Move without any special logic. */
  def movePiece(from: Pos, to: Pos): Board =
    get(from) match
      case None => this
      case Some(piece) =>
        val fromMask = squareMask(from)
        val toMask = squareMask(to)
        clearMask(toMask).clearMask(fromMask).addPiece(piece, toMask)

  private[chess] def applyMoveUnchecked(
    from: Pos,
    to: Pos,
    movingPiece: Piece,
    resultingPiece: Piece,
    enPassantCapturePos: Pos | Null = null,
    rookFrom: Pos | Null = null,
    rookTo: Pos | Null = null
  ): Board =
    val fromMask = squareMask(from)
    val toMask = squareMask(to)
    val enPassantMask =
      if enPassantCapturePos == null then 0L else squareMask(enPassantCapturePos.nn)
    val rookFromMask =
      if rookFrom == null then 0L else squareMask(rookFrom.nn)
    val rookToMask =
      if rookTo == null then 0L else squareMask(rookTo.nn)

    var whitePawns = whitePawnsBb
    var whiteKnights = whiteKnightsBb
    var whiteBishops = whiteBishopsBb
    var whiteRooks = whiteRooksBb
    var whiteQueens = whiteQueensBb
    var whiteKing = whiteKingBb
    var blackPawns = blackPawnsBb
    var blackKnights = blackKnightsBb
    var blackBishops = blackBishopsBb
    var blackRooks = blackRooksBb
    var blackQueens = blackQueensBb
    var blackKing = blackKingBb

    val clearedMasks = ~(fromMask | toMask | enPassantMask | rookFromMask | rookToMask)
    whitePawns &= clearedMasks
    whiteKnights &= clearedMasks
    whiteBishops &= clearedMasks
    whiteRooks &= clearedMasks
    whiteQueens &= clearedMasks
    whiteKing &= clearedMasks
    blackPawns &= clearedMasks
    blackKnights &= clearedMasks
    blackBishops &= clearedMasks
    blackRooks &= clearedMasks
    blackQueens &= clearedMasks
    blackKing &= clearedMasks

    (resultingPiece.color, resultingPiece.pieceType) match
      case (Color.White, PieceType.Pawn)   => whitePawns |= toMask
      case (Color.White, PieceType.Knight) => whiteKnights |= toMask
      case (Color.White, PieceType.Bishop) => whiteBishops |= toMask
      case (Color.White, PieceType.Rook)   => whiteRooks |= toMask
      case (Color.White, PieceType.Queen)  => whiteQueens |= toMask
      case (Color.White, PieceType.King)   => whiteKing |= toMask
      case (Color.Black, PieceType.Pawn)   => blackPawns |= toMask
      case (Color.Black, PieceType.Knight) => blackKnights |= toMask
      case (Color.Black, PieceType.Bishop) => blackBishops |= toMask
      case (Color.Black, PieceType.Rook)   => blackRooks |= toMask
      case (Color.Black, PieceType.Queen)  => blackQueens |= toMask
      case (Color.Black, PieceType.King)   => blackKing |= toMask

    if rookTo != null then
      val rookPiece = Piece(movingPiece.color, PieceType.Rook)
      (rookPiece.color, rookPiece.pieceType) match
        case (Color.White, PieceType.Rook) => whiteRooks |= rookToMask
        case (Color.Black, PieceType.Rook) => blackRooks |= rookToMask
        case _ => ()

    new Board(
      whitePawns, whiteKnights, whiteBishops, whiteRooks, whiteQueens, whiteKing,
      blackPawns, blackKnights, blackBishops, blackRooks, blackQueens, blackKing
    )

  def findKing(color: Color): Option[Pos] =
    bitboardHeadPos(if color == Color.White then whiteKingBb else blackKingBb)

  def allPiecesOf(color: Color): List[(Pos, Piece)] =
    val out = scala.collection.mutable.ListBuffer.empty[(Pos, Piece)]
    foreachPieceOf(color) { (pos, piece) =>
      out += ((pos, piece))
    }
    out.toList

  def foreachPiece(f: (Pos, Piece) => Unit): Unit =
    foreachBitboard(whitePawnsBb, Piece(Color.White, PieceType.Pawn), f)
    foreachBitboard(whiteKnightsBb, Piece(Color.White, PieceType.Knight), f)
    foreachBitboard(whiteBishopsBb, Piece(Color.White, PieceType.Bishop), f)
    foreachBitboard(whiteRooksBb, Piece(Color.White, PieceType.Rook), f)
    foreachBitboard(whiteQueensBb, Piece(Color.White, PieceType.Queen), f)
    foreachBitboard(whiteKingBb, Piece(Color.White, PieceType.King), f)
    foreachBitboard(blackPawnsBb, Piece(Color.Black, PieceType.Pawn), f)
    foreachBitboard(blackKnightsBb, Piece(Color.Black, PieceType.Knight), f)
    foreachBitboard(blackBishopsBb, Piece(Color.Black, PieceType.Bishop), f)
    foreachBitboard(blackRooksBb, Piece(Color.Black, PieceType.Rook), f)
    foreachBitboard(blackQueensBb, Piece(Color.Black, PieceType.Queen), f)
    foreachBitboard(blackKingBb, Piece(Color.Black, PieceType.King), f)

  def foreachPieceOf(color: Color)(f: (Pos, Piece) => Unit): Unit =
    if color == Color.White then
      foreachBitboard(whitePawnsBb, Piece(Color.White, PieceType.Pawn), f)
      foreachBitboard(whiteKnightsBb, Piece(Color.White, PieceType.Knight), f)
      foreachBitboard(whiteBishopsBb, Piece(Color.White, PieceType.Bishop), f)
      foreachBitboard(whiteRooksBb, Piece(Color.White, PieceType.Rook), f)
      foreachBitboard(whiteQueensBb, Piece(Color.White, PieceType.Queen), f)
      foreachBitboard(whiteKingBb, Piece(Color.White, PieceType.King), f)
    else
      foreachBitboard(blackPawnsBb, Piece(Color.Black, PieceType.Pawn), f)
      foreachBitboard(blackKnightsBb, Piece(Color.Black, PieceType.Knight), f)
      foreachBitboard(blackBishopsBb, Piece(Color.Black, PieceType.Bishop), f)
      foreachBitboard(blackRooksBb, Piece(Color.Black, PieceType.Rook), f)
      foreachBitboard(blackQueensBb, Piece(Color.Black, PieceType.Queen), f)
      foreachBitboard(blackKingBb, Piece(Color.Black, PieceType.King), f)

  def toFenPlacement: String =
    val ranks = scala.collection.mutable.ListBuffer.empty[String]
    var row = 7
    while row >= 0 do
      val parts = scala.collection.mutable.ListBuffer.empty[String]
      var emptyCount = 0
      var col = 0
      while col < 8 do
        get(Pos(col, row)) match
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

  private def occupancyOf(color: Color): Long =
    if color == Color.White then whiteOccupancyBb else blackOccupancyBb

  private def whiteOccupancyBb: Long =
    whitePawnsBb | whiteKnightsBb | whiteBishopsBb | whiteRooksBb | whiteQueensBb | whiteKingBb

  private def blackOccupancyBb: Long =
    blackPawnsBb | blackKnightsBb | blackBishopsBb | blackRooksBb | blackQueensBb | blackKingBb

  private def occupiedBb: Long =
    whiteOccupancyBb | blackOccupancyBb

  private def pieceAtMask(mask: Long): Option[Piece] =
    val piece = pieceAtMaskOrNull(mask)
    if piece == null then None else Some(piece.nn)

  private def pieceAtMaskOrNull(mask: Long): Piece | Null =
    if (whitePawnsBb & mask) != 0L then Piece(Color.White, PieceType.Pawn)
    else if (whiteKnightsBb & mask) != 0L then Piece(Color.White, PieceType.Knight)
    else if (whiteBishopsBb & mask) != 0L then Piece(Color.White, PieceType.Bishop)
    else if (whiteRooksBb & mask) != 0L then Piece(Color.White, PieceType.Rook)
    else if (whiteQueensBb & mask) != 0L then Piece(Color.White, PieceType.Queen)
    else if (whiteKingBb & mask) != 0L then Piece(Color.White, PieceType.King)
    else if (blackPawnsBb & mask) != 0L then Piece(Color.Black, PieceType.Pawn)
    else if (blackKnightsBb & mask) != 0L then Piece(Color.Black, PieceType.Knight)
    else if (blackBishopsBb & mask) != 0L then Piece(Color.Black, PieceType.Bishop)
    else if (blackRooksBb & mask) != 0L then Piece(Color.Black, PieceType.Rook)
    else if (blackQueensBb & mask) != 0L then Piece(Color.Black, PieceType.Queen)
    else if (blackKingBb & mask) != 0L then Piece(Color.Black, PieceType.King)
    else null

  private def clearMask(mask: Long): Board =
    copy(
      whitePawnsBb = whitePawnsBb & ~mask,
      whiteKnightsBb = whiteKnightsBb & ~mask,
      whiteBishopsBb = whiteBishopsBb & ~mask,
      whiteRooksBb = whiteRooksBb & ~mask,
      whiteQueensBb = whiteQueensBb & ~mask,
      whiteKingBb = whiteKingBb & ~mask,
      blackPawnsBb = blackPawnsBb & ~mask,
      blackKnightsBb = blackKnightsBb & ~mask,
      blackBishopsBb = blackBishopsBb & ~mask,
      blackRooksBb = blackRooksBb & ~mask,
      blackQueensBb = blackQueensBb & ~mask,
      blackKingBb = blackKingBb & ~mask
    )

  private def addPiece(piece: Piece, mask: Long): Board =
    (piece.color, piece.pieceType) match
      case (Color.White, PieceType.Pawn)   => copy(whitePawnsBb = whitePawnsBb | mask)
      case (Color.White, PieceType.Knight) => copy(whiteKnightsBb = whiteKnightsBb | mask)
      case (Color.White, PieceType.Bishop) => copy(whiteBishopsBb = whiteBishopsBb | mask)
      case (Color.White, PieceType.Rook)   => copy(whiteRooksBb = whiteRooksBb | mask)
      case (Color.White, PieceType.Queen)  => copy(whiteQueensBb = whiteQueensBb | mask)
      case (Color.White, PieceType.King)   => copy(whiteKingBb = whiteKingBb | mask)
      case (Color.Black, PieceType.Pawn)   => copy(blackPawnsBb = blackPawnsBb | mask)
      case (Color.Black, PieceType.Knight) => copy(blackKnightsBb = blackKnightsBb | mask)
      case (Color.Black, PieceType.Bishop) => copy(blackBishopsBb = blackBishopsBb | mask)
      case (Color.Black, PieceType.Rook)   => copy(blackRooksBb = blackRooksBb | mask)
      case (Color.Black, PieceType.Queen)  => copy(blackQueensBb = blackQueensBb | mask)
      case (Color.Black, PieceType.King)   => copy(blackKingBb = blackKingBb | mask)

object Board:
  private[model] def squareIndex(pos: Pos): Int =
    pos.row * 8 + pos.col

  private[model] def squareMask(pos: Pos): Long =
    1L << squareIndex(pos)

  private[model] def posFromSquareIndex(idx: Int): Pos =
    Pos(idx & 7, idx >>> 3)

  private def bitboardHeadPos(bb: Long): Option[Pos] =
    if bb == 0L then None
    else Some(posFromSquareIndex(java.lang.Long.numberOfTrailingZeros(bb)))

  private def foreachBitboard(bb0: Long, piece: Piece, f: (Pos, Piece) => Unit): Unit =
    var bb = bb0
    while bb != 0L do
      val idx = java.lang.Long.numberOfTrailingZeros(bb)
      f(posFromSquareIndex(idx), piece)
      bb &= (bb - 1L)

  /** Standard starting position. */
  def initial: Board =
    new Board(
      whitePawnsBb = 0x000000000000FF00L,
      whiteKnightsBb = 0x0000000000000042L,
      whiteBishopsBb = 0x0000000000000024L,
      whiteRooksBb = 0x0000000000000081L,
      whiteQueensBb = 0x0000000000000008L,
      whiteKingBb = 0x0000000000000010L,
      blackPawnsBb = 0x00FF000000000000L,
      blackKnightsBb = 0x4200000000000000L,
      blackBishopsBb = 0x2400000000000000L,
      blackRooksBb = 0x8100000000000000L,
      blackQueensBb = 0x0800000000000000L,
      blackKingBb = 0x1000000000000000L
    )

  def empty: Board =
    new Board(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

  def apply(pieces: Map[Pos, Piece]): Board =
    var whitePawns = 0L
    var whiteKnights = 0L
    var whiteBishops = 0L
    var whiteRooks = 0L
    var whiteQueens = 0L
    var whiteKing = 0L
    var blackPawns = 0L
    var blackKnights = 0L
    var blackBishops = 0L
    var blackRooks = 0L
    var blackQueens = 0L
    var blackKing = 0L
    pieces.foreach { case (pos, piece) =>
      val mask = squareMask(pos)
      (piece.color, piece.pieceType) match
        case (Color.White, PieceType.Pawn)   => whitePawns |= mask
        case (Color.White, PieceType.Knight) => whiteKnights |= mask
        case (Color.White, PieceType.Bishop) => whiteBishops |= mask
        case (Color.White, PieceType.Rook)   => whiteRooks |= mask
        case (Color.White, PieceType.Queen)  => whiteQueens |= mask
        case (Color.White, PieceType.King)   => whiteKing |= mask
        case (Color.Black, PieceType.Pawn)   => blackPawns |= mask
        case (Color.Black, PieceType.Knight) => blackKnights |= mask
        case (Color.Black, PieceType.Bishop) => blackBishops |= mask
        case (Color.Black, PieceType.Rook)   => blackRooks |= mask
        case (Color.Black, PieceType.Queen)  => blackQueens |= mask
        case (Color.Black, PieceType.King)   => blackKing |= mask
    }
    new Board(
      whitePawns, whiteKnights, whiteBishops, whiteRooks, whiteQueens, whiteKing,
      blackPawns, blackKnights, blackBishops, blackRooks, blackQueens, blackKing
    )

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

  /** Public convenience method for parsing a FEN placement string. */
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

private[chess] object ZobristHash:
  private val zobristRandom = new SplittableRandom(0x4D595DF4D0F33173L)
  private def nextZobrist(): Long =
    val x = zobristRandom.nextLong()
    if x != 0L then x else 0x9E3779B97F4A7C15L

  private val pieceSquareZobrist: Array[Long] = Array.fill(12 * 64)(nextZobrist())
  private val castlingZobrist: Array[Long] = Array.fill(4)(nextZobrist())
  private val enPassantZobrist: Array[Long] = Array.fill(64)(nextZobrist())
  private val blackToMoveZobrist: Long = nextZobrist()

  def compute(state: GameState): Long =
    compute(state.board, state.activeColor, state.castlingRights, state.enPassantTarget)

  def compute(
    board: Board,
    activeColor: Color,
    castlingRights: CastlingRights,
    enPassantTarget: Option[Pos]
  ): Long =
    var h = 0L
    board.foreachPiece { (pos, piece) =>
      h ^= pieceSquareZobrist((pieceHashIndex(piece) * 64) + Board.squareIndex(pos))
    }
    if activeColor == Color.Black then h ^= blackToMoveZobrist
    h = xorCastlingRights(h, castlingRights)
    xorEnPassantTarget(h, enPassantTarget)

  def advance(
    currentHash: Long,
    activeColor: Color,
    castlingRights: CastlingRights,
    enPassantTarget: Option[Pos],
    move: Move,
    movingPiece: Piece,
    resultingPiece: Piece,
    capturedPiece: Piece | Null,
    capturedPos: Pos | Null,
    newCastlingRights: CastlingRights,
    newEnPassantTarget: Option[Pos],
    rookFrom: Pos | Null = null,
    rookTo: Pos | Null = null
  ): Long =
    var h = currentHash
    h ^= pieceSquareZobrist((pieceHashIndex(movingPiece) * 64) + Board.squareIndex(move.from))
    h ^= pieceSquareZobrist((pieceHashIndex(resultingPiece) * 64) + Board.squareIndex(move.to))
    if capturedPiece != null && capturedPos != null then
      h ^= pieceSquareZobrist((pieceHashIndex(capturedPiece.nn) * 64) + Board.squareIndex(capturedPos.nn))
    if rookFrom != null && rookTo != null then
      val rookPiece = Piece(activeColor, PieceType.Rook)
      h ^= pieceSquareZobrist((pieceHashIndex(rookPiece) * 64) + Board.squareIndex(rookFrom.nn))
      h ^= pieceSquareZobrist((pieceHashIndex(rookPiece) * 64) + Board.squareIndex(rookTo.nn))
    h ^= blackToMoveZobrist
    h = xorCastlingRights(h, castlingRights)
    h = xorCastlingRights(h, newCastlingRights)
    h = xorEnPassantTarget(h, enPassantTarget)
    xorEnPassantTarget(h, newEnPassantTarget)

  def advanceNullMove(currentHash: Long, enPassantTarget: Option[Pos]): Long =
    xorEnPassantTarget(currentHash ^ blackToMoveZobrist, enPassantTarget)

  private def xorCastlingRights(hash: Long, castlingRights: CastlingRights): Long =
    var h = hash
    if castlingRights.whiteKingSide then h ^= castlingZobrist(0)
    if castlingRights.whiteQueenSide then h ^= castlingZobrist(1)
    if castlingRights.blackKingSide then h ^= castlingZobrist(2)
    if castlingRights.blackQueenSide then h ^= castlingZobrist(3)
    h

  private def xorEnPassantTarget(hash: Long, enPassantTarget: Option[Pos]): Long =
    enPassantTarget match
      case Some(pos) => hash ^ enPassantZobrist(Board.squareIndex(pos))
      case None => hash

  private def pieceHashIndex(piece: Piece): Int =
    val colorOffset = if piece.color == Color.White then 0 else 6
    colorOffset + (piece.pieceType match
      case PieceType.Pawn => 0
      case PieceType.Knight => 1
      case PieceType.Bishop => 2
      case PieceType.Rook => 3
      case PieceType.Queen => 4
      case PieceType.King => 5
    )

sealed trait GameState:
  def board: Board
  def activeColor: Color
  def castlingRights: CastlingRights
  def enPassantTarget: Option[Pos]
  def halfMoveClock: Int
  def fullMoveNumber: Int
  def history: Vector[GameState]
  protected def cachedPositionHashValue: Long
  protected def cachedHashBoard: Board | Null
  protected def cachedHashCastlingRights: CastlingRights | Null
  protected def cachedHashEnPassantTarget: Option[Pos] | Null
  protected def cachedRepetitionCountsValue: Map[Long, Int] | Null

  def withHistory: GameState

  // Cached king squares for this immutable state (avoids repeated board scans).
  lazy val whiteKingPos: Option[Pos] = board.findKing(Color.White)
  lazy val blackKingPos: Option[Pos] = board.findKing(Color.Black)
  lazy val positionHash: Long =
    if cachedHashBoard == board &&
      cachedHashCastlingRights == castlingRights &&
      cachedHashEnPassantTarget == enPassantTarget
    then cachedPositionHashValue
    else ZobristHash.compute(this)
  lazy val repetitionCounts: Map[Long, Int] =
    val cached = cachedRepetitionCountsValue
    if cached != null && cached.contains(positionHash) then cached.nn
    else Map(positionHash -> 1)
  def kingPos(color: Color): Option[Pos] =
    if color == Color.White then whiteKingPos else blackKingPos

  def toFen: String =
    val enPassant = enPassantTarget.map(_.toAlgebraic).getOrElse("-")
    s"${board.toFenPlacement} ${GameState.colorToFen(activeColor)} ${castlingRights.toFen} $enPassant $halfMoveClock $fullMoveNumber"

extension (s: GameState)
  def copy(
    board: Board = s.board,
    castlingRights: CastlingRights = s.castlingRights,
    enPassantTarget: Option[Pos] = s.enPassantTarget,
    halfMoveClock: Int = s.halfMoveClock,
    fullMoveNumber: Int = s.fullMoveNumber,
    history: Vector[GameState] = s.history,
    repetitionCounts: Map[Long, Int] = s.repetitionCounts
  ): GameState = s match
    case _: WhiteToMove => GameState.white(board, castlingRights, enPassantTarget, halfMoveClock, fullMoveNumber, history, repetitionCounts = repetitionCounts)
    case _: BlackToMove => GameState.black(board, castlingRights, enPassantTarget, halfMoveClock, fullMoveNumber, history, repetitionCounts = repetitionCounts)

  def withActiveColor(color: Color): GameState = (s, color) match
    case (w: WhiteToMove, Color.White) => w
    case (b: BlackToMove, Color.Black) => b
    case (w: WhiteToMove, Color.Black) => GameState.black(w.board, w.castlingRights, w.enPassantTarget, w.halfMoveClock, w.fullMoveNumber, w.history)
    case (b: BlackToMove, Color.White) => GameState.white(b.board, b.castlingRights, b.enPassantTarget, b.halfMoveClock, b.fullMoveNumber, b.history)

  /** Returns all pieces of the given color that have been captured. */
  def capturedPieces(color: Color): List[PieceType] =
    val startingCounts = Map(
      PieceType.Pawn -> 8,
      PieceType.Knight -> 2,
      PieceType.Bishop -> 2,
      PieceType.Rook -> 2,
      PieceType.Queen -> 1
    )
    val currentPieces = s.board.allPiecesOf(color).map(_._2.pieceType)
    val currentCounts = currentPieces.groupBy(identity).view.mapValues(_.size).toMap

    startingCounts.flatMap { (pt, initialCount) =>
      val currentCount = currentCounts.getOrElse(pt, 0)
      List.fill(Math.max(0, initialCount - currentCount))(pt)
    }.toList

  def materialInfo: MaterialInfo =
    val capW = capturedPieces(Color.White)
    val capB = capturedPieces(Color.Black)

    val valW = capW.map(PieceType.pieceValue).sum
    val valB = capB.map(PieceType.pieceValue).sum

    val pieceOrder = Vector(PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight, PieceType.Pawn)
    val sortedW = capW.sortBy(pieceOrder.indexOf)
    val sortedB = capB.sortBy(pieceOrder.indexOf)

    MaterialInfo(
      whiteCapturedSymbols = sortedW.map(pt => Piece(Color.White, pt).symbol),
      blackCapturedSymbols = sortedB.map(pt => Piece(Color.Black, pt).symbol),
      whiteAdvantage = Math.max(0, valB - valW),
      blackAdvantage = Math.max(0, valW - valB)
    )

case class WhiteToMove(
  board: Board,
  castlingRights: CastlingRights,
  enPassantTarget: Option[Pos],
  halfMoveClock: Int,
  fullMoveNumber: Int,
  history: Vector[GameState] = Vector.empty,
  protected val cachedPositionHashValue: Long = 0L,
  protected val cachedHashBoard: Board | Null = null,
  protected val cachedHashCastlingRights: CastlingRights | Null = null,
  protected val cachedHashEnPassantTarget: Option[Pos] | Null = null,
  protected val cachedRepetitionCountsValue: Map[Long, Int] | Null = null
) extends GameState:
  val activeColor: Color = Color.White
  def withHistory: GameState =
    GameState.white(
      board,
      castlingRights,
      enPassantTarget,
      halfMoveClock,
      fullMoveNumber,
      history :+ this,
      positionHash = positionHash,
      repetitionCounts = repetitionCounts
    )

case class BlackToMove(
  board: Board,
  castlingRights: CastlingRights,
  enPassantTarget: Option[Pos],
  halfMoveClock: Int,
  fullMoveNumber: Int,
  history: Vector[GameState] = Vector.empty,
  protected val cachedPositionHashValue: Long = 0L,
  protected val cachedHashBoard: Board | Null = null,
  protected val cachedHashCastlingRights: CastlingRights | Null = null,
  protected val cachedHashEnPassantTarget: Option[Pos] | Null = null,
  protected val cachedRepetitionCountsValue: Map[Long, Int] | Null = null
) extends GameState:
  val activeColor: Color = Color.Black
  def withHistory: GameState =
    GameState.black(
      board,
      castlingRights,
      enPassantTarget,
      halfMoveClock,
      fullMoveNumber,
      history :+ this,
      positionHash = positionHash,
      repetitionCounts = repetitionCounts
    )

object GameState:
  val initialFen: String = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private val NoCachedHash = Long.MinValue

  private[chess] def white(
    board: Board,
    castlingRights: CastlingRights,
    enPassantTarget: Option[Pos],
    halfMoveClock: Int,
    fullMoveNumber: Int,
    history: Vector[GameState] = Vector.empty,
    positionHash: Long = NoCachedHash,
    repetitionCounts: Map[Long, Int] | Null = null
  ): WhiteToMove =
    val hash =
      if positionHash != NoCachedHash then positionHash
      else ZobristHash.compute(board, Color.White, castlingRights, enPassantTarget)
    val reps =
      if repetitionCounts != null && repetitionCounts.contains(hash) then repetitionCounts
      else Map(hash -> 1)
    WhiteToMove(board, castlingRights, enPassantTarget, halfMoveClock, fullMoveNumber, history, hash, board, castlingRights, enPassantTarget, reps)

  private[chess] def black(
    board: Board,
    castlingRights: CastlingRights,
    enPassantTarget: Option[Pos],
    halfMoveClock: Int,
    fullMoveNumber: Int,
    history: Vector[GameState] = Vector.empty,
    positionHash: Long = NoCachedHash,
    repetitionCounts: Map[Long, Int] | Null = null
  ): BlackToMove =
    val hash =
      if positionHash != NoCachedHash then positionHash
      else ZobristHash.compute(board, Color.Black, castlingRights, enPassantTarget)
    val reps =
      if repetitionCounts != null && repetitionCounts.contains(hash) then repetitionCounts
      else Map(hash -> 1)
    BlackToMove(board, castlingRights, enPassantTarget, halfMoveClock, fullMoveNumber, history, hash, board, castlingRights, enPassantTarget, reps)

  private[chess] def advanceRepetitionCounts(
    previous: Map[Long, Int],
    newPositionHash: Long,
    irreversible: Boolean
  ): Map[Long, Int] =
    if irreversible then Map(newPositionHash -> 1)
    else previous.updated(newPositionHash, previous.getOrElse(newPositionHash, 0) + 1)

  def initial: GameState = white(
    board = Board.initial,
    castlingRights = CastlingRights(),
    enPassantTarget = None,
    halfMoveClock = 0,
    fullMoveNumber = 1,
    history = Vector.empty
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
                                    white(board, castlingRights, enPassantTarget, halfMoveClock, fullMoveNumber)
                                  else
                                    black(board, castlingRights, enPassantTarget, halfMoveClock, fullMoveNumber)
                                )
      case _ => Left("FEN must contain 6 space-separated fields")

  private[model] def colorToFen(color: Color): String = color match
    case Color.White => "w"
    case Color.Black => "b"

  private def colorFromFen(value: String): Either[String, Color] = value match
    case "w" => Right(Color.White)
    case "b" => Right(Color.Black)
    case _ => Left("Invalid active color in FEN")

  private def parseEnPassant(value: String): Either[String, Option[Pos]] =
    if value == "-" then Right(None)
    else
      Pos.fromAlgebraic(value) match
        case Some(pos) if pos.row == 2 || pos.row == 5 => Right(Some(pos))
        case Some(_) => Left("Invalid en passant target rank in FEN")
        case None => Left("Invalid en passant target square in FEN")

  private def parseNonNegativeInt(value: String, label: String): Either[String, Int] =
    value.toIntOption match
      case Some(number) if number >= 0 => Right(number)
      case _ => Left(s"Invalid $label in FEN")

  private def parsePositiveInt(value: String, label: String): Either[String, Int] =
    value.toIntOption match
      case Some(number) if number > 0 => Right(number)
      case _ => Left(s"Invalid $label in FEN")
