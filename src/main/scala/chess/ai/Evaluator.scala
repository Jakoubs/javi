package chess.ai

import chess.model.*
import java.io.{File, PrintWriter}
import scala.io.Source
import scala.util.Try
import java.util.concurrent.atomic.AtomicReference

/**
 * Evaluation function for board states.
 * Uses material weights and Piece-Square Tables (PSTs).
 * Weights are thread-safe via AtomicReference and can be updated for learning.
 */
object Evaluator:

  // --- Thread-safe Material Weights ---
  // AtomicReference[Double] allows concurrent compare-and-swap updates
  private val pawnWeight   = AtomicReference(100.0)
  private val knightWeight = AtomicReference(320.0)
  private val bishopWeight = AtomicReference(330.0)
  private val rookWeight   = AtomicReference(500.0)
  private val queenWeight  = AtomicReference(900.0)

  // --- Trainable Opening Heuristics ---
  private val openingCenterPawn       = AtomicReference(50.0)
  private val openingCenterKnight     = AtomicReference(40.0)
  private val openingUndevelopedMinor = AtomicReference(80.0)
  private val openingPawnPushPenalty  = AtomicReference(40.0)
  private val openingEarlyQueen       = AtomicReference(150.0)
  private val openingCastledBonus     = AtomicReference(250.0)
  private val openingLostCastle       = AtomicReference(150.0)
  private val openingUncastled        = AtomicReference(200.0)
  private val openingConnectedRooks   = AtomicReference(80.0)
  private val openingWeakPawnF        = AtomicReference(40.0)
  private val openingWeakPawnH        = AtomicReference(30.0)

  private val weightsFile = "ai_weights.txt"

  // Thread-safe atomic add: loops until CAS succeeds
  private def atomicAdd(ref: AtomicReference[Double], delta: Double): Unit =
    var updated = false
    while !updated do
      val current = ref.get()
      updated = ref.compareAndSet(current, current + delta)

  /**
   * Evaluate the board from White's perspective.
   * Positive = White advantage, Negative = Black advantage.
   */
  def evaluate(state: GameState): Double =
    val board = state.board
    var score = 0.0

    for (pos, piece) <- board.pieces do
      val material = weightOf(piece.pieceType)
      val pstBonus = pstScore(piece, pos)
      
      if piece.color == Color.White then
        score += material + pstBonus
      else
        score -= material + pstBonus

    // Bestimme Spielphase (Opening Weight)
    // Maximale Materialsumme (ohne König) ist ca. 7800. 
    // Eröffnungs-Regeln verblassen fließend zwischen 6500 und 4500.
    val totalMaterial = board.pieces.values.filter(_.pieceType != PieceType.King).map(p => weightOf(p.pieceType)).sum
    val openingWeight = math.max(0.0, math.min(1.0, (totalMaterial - 4500) / 2000.0))
    
    if openingWeight > 0 then
      val wOpening = evaluateOpening(state, Color.White)
      val bOpening = evaluateOpening(state, Color.Black)
      score += (wOpening - bOpening) * openingWeight

    score

  // --- Opening Heuristics ---
  private def evaluateOpening(state: GameState, color: Color): Double =
    var score = 0.0
    val board = state.board
    val startRow = if color == Color.White then 0 else 7
    val pawnRow = if color == Color.White then 1 else 6
    val forward = if color == Color.White then 1 else -1

    // 1 & 7. Center Control
    val ePawnPos = Pos(4, startRow + 3 * forward)
    val dPawnPos = Pos(3, startRow + 3 * forward)
    if board.get(ePawnPos).contains(Piece(color, PieceType.Pawn)) then score += openingCenterPawn.get()
    if board.get(dPawnPos).contains(Piece(color, PieceType.Pawn)) then score += openingCenterPawn.get()
    for c <- Seq(2, 5) do
      val nPos = Pos(c, startRow + 2 * forward)
      if board.get(nPos).contains(Piece(color, PieceType.Knight)) then score += openingCenterKnight.get()

    // 2. Develop minor pieces quickly (penalty for undeveloped)
    var undevelopedMinors = 0
    if board.get(Pos(1, startRow)).contains(Piece(color, PieceType.Knight)) then undevelopedMinors += 1
    if board.get(Pos(6, startRow)).contains(Piece(color, PieceType.Knight)) then undevelopedMinors += 1
    if board.get(Pos(2, startRow)).contains(Piece(color, PieceType.Bishop)) then undevelopedMinors += 1
    if board.get(Pos(5, startRow)).contains(Piece(color, PieceType.Bishop)) then undevelopedMinors += 1
    score -= undevelopedMinors * openingUndevelopedMinor.get()

    // 5. Moved pawns vs Development
    val unmovedPawns = (0 to 7).count(c => board.get(Pos(c, pawnRow)).contains(Piece(color, PieceType.Pawn)))
    val movedPawns = 8 - unmovedPawns
    if movedPawns > 3 && undevelopedMinors > 1 then
      score -= (movedPawns - 3) * openingPawnPushPenalty.get()

    // 6. Queen out too early
    if !board.get(Pos(3, startRow)).contains(Piece(color, PieceType.Queen)) && undevelopedMinors > 1 then
      score -= openingEarlyQueen.get()

    // 3. King Safety (Castling rights)
    val kingPos = board.findKing(color)
    val castlingRights = state.castlingRights
    val kSideAllowed = if color == Color.White then castlingRights.whiteKingSide else castlingRights.blackKingSide
    val qSideAllowed = if color == Color.White then castlingRights.whiteQueenSide else castlingRights.blackQueenSide
    
    val kingMoved = kingPos.exists(p => p != Pos(4, startRow))
    if kingMoved then
      if kingPos.exists(p => p.col == 6 || p.col == 2) then
        score += openingCastledBonus.get()
      else
        if !kSideAllowed && !qSideAllowed then score -= openingLostCastle.get()
    else
      if !kSideAllowed && !qSideAllowed then score -= openingUncastled.get()

    // 4. Connect Rooks
    if board.get(Pos(0, startRow)).contains(Piece(color, PieceType.Rook)) &&
       board.get(Pos(7, startRow)).contains(Piece(color, PieceType.Rook)) then
         val anyPieceBetween = (1 to 6).map(c => board.get(Pos(c, startRow))).exists(_.isDefined)
         if !anyPieceBetween then score += openingConnectedRooks.get()

    // 8. Avoid unnecessary weaknesses (f & h pawns drawn early)
    if !board.get(Pos(5, pawnRow)).contains(Piece(color, PieceType.Pawn)) then score -= openingWeakPawnF.get()
    if !board.get(Pos(7, pawnRow)).contains(Piece(color, PieceType.Pawn)) then score -= openingWeakPawnH.get()

    score

  private def weightOf(pt: PieceType): Double = pt match
    case PieceType.Pawn   => pawnWeight.get()
    case PieceType.Knight => knightWeight.get()
    case PieceType.Bishop => bishopWeight.get()
    case PieceType.Rook   => rookWeight.get()
    case PieceType.Queen  => queenWeight.get()
    case PieceType.King   => 20000.0
  
  /** Piece-Square Tables — reads from trainable pstWeights */
  private def pstScore(piece: Piece, pos: Pos): Double =
    val (c, r) = (pos.col, pos.row)
    val (relC, relR) = if piece.color == Color.White then (c, r) else (c, 7 - r)
    val index = relR * 8 + relC
    pstWeights(piece.pieceType)(index).get()

  // --- PST Tables (Simplified) ---
  private val pawnPst = Array(
    Array(0,  0,  0,  0,  0,  0,  0,  0),
    Array(50, 50, 50, 50, 50, 50, 50, 50),
    Array(10, 10, 20, 30, 30, 20, 10, 10),
    Array(5,  5, 10, 25, 25, 10,  5,  5),
    Array(0,  0,  0, 20, 20,  0,  0,  0),
    Array(5, -5,-10,  0,  0,-10, -5,  5),
    Array(5, 10, 10,-20,-20, 10, 10,  5),
    Array(0,  0,  0,  0,  0,  0,  0,  0)
  )

  private val knightPst = Array(
    Array(-50,-40,-30,-30,-30,-30,-40,-50),
    Array(-40,-20,  0,  0,  0,  0,-20,-40),
    Array(-30,  0, 10, 15, 15, 10,  0,-30),
    Array(-30,  5, 15, 20, 20, 15,  5,-30),
    Array(-30,  0, 15, 20, 20, 15,  0,-30),
    Array(-30,  5, 10, 15, 15, 10,  5,-30),
    Array(-40,-20,  0,  5,  5,  0,-20,-40),
    Array(-50,-40,-30,-30,-30,-30,-40,-50)
  )

  private val bishopPst = Array(
    Array(-20,-10,-10,-10,-10,-10,-10,-20),
    Array(-10,  0,  0,  0,  0,  0,  0,-10),
    Array(-10,  0,  5, 10, 10,  5,  0,-10),
    Array(-10,  5,  5, 10, 10,  5,  5,-10),
    Array(-10,  0, 10, 10, 10, 10,  0,-10),
    Array(-10, 10, 10, 10, 10, 10, 10,-10),
    Array(-10,  5,  0,  0,  0,  0,  5,-10),
    Array(-20,-10,-10,-10,-10,-10,-10,-20)
  )

  private val rookPst = Array(
    Array( 0,  0,  0,  0,  0,  0,  0,  0),
    Array( 5, 10, 10, 10, 10, 10, 10,  5),
    Array(-5,  0,  0,  0,  0,  0,  0, -5),
    Array(-5,  0,  0,  0,  0,  0,  0, -5),
    Array(-5,  0,  0,  0,  0,  0,  0, -5),
    Array(-5,  0,  0,  0,  0,  0,  0, -5),
    Array(-5,  0,  0,  0,  0,  0,  0, -5),
    Array( 0,  0,  0,  5,  5,  0,  0,  0)
  )

  private val queenPst = Array(
    Array(-20,-10,-10, -5, -5,-10,-10,-20),
    Array(-10,  0,  0,  0,  0,  0,  0,-10),
    Array(-10,  0,  5,  5,  5,  5,  0,-10),
    Array( -5,  0,  5,  5,  5,  5,  0, -5),
    Array(  0,  0,  5,  5,  5,  5,  0, -5),
    Array(-10,  5,  5,  5,  5,  5,  0,-10),
    Array(-10,  0,  5,  0,  0,  0,  0,-10),
    Array(-20,-10,-10, -5, -5,-10,-10,-20)
  )

  private val kingPst = Array(
    Array(-30,-40,-40,-50,-50,-40,-40,-30),
    Array(-30,-40,-40,-50,-50,-40,-40,-30),
    Array(-30,-40,-40,-50,-50,-40,-40,-30),
    Array(-30,-40,-40,-50,-50,-40,-40,-30),
    Array(-20,-30,-30,-40,-40,-30,-30,-20),
    Array(-10,-20,-20,-20,-20,-20,-20,-10),
    Array( 20, 20,  0,  0,  0,  0, 20, 20),
    Array( 20, 30, 10,  0,  0, 10, 30, 20)
  )

  // --- Trainable PST weights (initialized from hardcoded tables above) ---
  // IMPORTANT: This MUST be defined AFTER the PST arrays above!
  private val pstWeights: Map[PieceType, Array[AtomicReference[Double]]] = {
    val tables: Array[(PieceType, Array[Array[Int]])] = Array(
      (PieceType.Pawn,   pawnPst),
      (PieceType.Knight, knightPst),
      (PieceType.Bishop, bishopPst),
      (PieceType.Rook,   rookPst),
      (PieceType.Queen,  queenPst),
      (PieceType.King,   kingPst)
    )
    tables.map { case (pt, table) =>
      val flat = new Array[AtomicReference[Double]](64)
      for (r <- 0 until 8; c <- 0 until 8) do
        flat(r * 8 + c) = AtomicReference(table(r)(c).toDouble)
      pt -> flat
    }.toMap
  }

  // --- Persistence & Learning ---
  
  /**
   * Load weights from a file. 
   * @param path Optional file path; defaults to "ai_weights.txt"
   */
  def loadWeights(path: String = weightsFile): Unit =
    Try {
      val source = Source.fromFile(path)
      val lines = source.getLines().toList
      source.close()
      if lines.size >= 5 then
        pawnWeight.set(lines(0).toDouble)
        knightWeight.set(lines(1).toDouble)
        bishopWeight.set(lines(2).toDouble)
        rookWeight.set(lines(3).toDouble)
        queenWeight.set(lines(4).toDouble)
      // Load PSTs (lines 5..388)
      if lines.size >= 389 then
        var idx = 5
        val pieceOrder = Array(PieceType.Pawn, PieceType.Knight, PieceType.Bishop, PieceType.Rook, PieceType.Queen, PieceType.King)
        for pt <- pieceOrder do
          val arr = pstWeights(pt)
          for i <- 0 until 64 do
            arr(i).set(lines(idx).toDouble)
            idx += 1
      // Load Openings (lines 389..399)
      if lines.size >= 400 then
        var idx = 389
        openingCenterPawn.set(lines(idx).toDouble); idx += 1
        openingCenterKnight.set(lines(idx).toDouble); idx += 1
        openingUndevelopedMinor.set(lines(idx).toDouble); idx += 1
        openingPawnPushPenalty.set(lines(idx).toDouble); idx += 1
        openingEarlyQueen.set(lines(idx).toDouble); idx += 1
        openingCastledBonus.set(lines(idx).toDouble); idx += 1
        openingLostCastle.set(lines(idx).toDouble); idx += 1
        openingUncastled.set(lines(idx).toDouble); idx += 1
        openingConnectedRooks.set(lines(idx).toDouble); idx += 1
        openingWeakPawnF.set(lines(idx).toDouble); idx += 1
        openingWeakPawnH.set(lines(idx).toDouble)
    }

  /**
   * Save current weights to a file (5 material + 384 PST = 389 total).
   */
  def saveWeights(path: String = weightsFile): Unit = synchronized {
    val out = new PrintWriter(new File(path))
    out.println(pawnWeight.get())
    out.println(knightWeight.get())
    out.println(bishopWeight.get())
    out.println(rookWeight.get())
    out.println(queenWeight.get())
    val pieceOrder = Array(PieceType.Pawn, PieceType.Knight, PieceType.Bishop, PieceType.Rook, PieceType.Queen, PieceType.King)
    for pt <- pieceOrder do
      val arr = pstWeights(pt)
      for i <- 0 until 64 do
        out.println(arr(i).get())
    
    // Save Openings
    out.println(openingCenterPawn.get())
    out.println(openingCenterKnight.get())
    out.println(openingUndevelopedMinor.get())
    out.println(openingPawnPushPenalty.get())
    out.println(openingEarlyQueen.get())
    out.println(openingCastledBonus.get())
    out.println(openingLostCastle.get())
    out.println(openingUncastled.get())
    out.println(openingConnectedRooks.get())
    out.println(openingWeakPawnF.get())
    out.println(openingWeakPawnH.get())

    out.close()
  }

  // Thread-safe weight update — material + optional positional
  def updateWeights(delta: Double, pieceType: PieceType, color: Color, pos: Option[Pos] = None): Unit =
    // Material update
    pieceType match
      case PieceType.Pawn   => atomicAdd(pawnWeight,   delta)
      case PieceType.Knight => atomicAdd(knightWeight, delta)
      case PieceType.Bishop => atomicAdd(bishopWeight, delta)
      case PieceType.Rook   => atomicAdd(rookWeight,   delta)
      case PieceType.Queen  => atomicAdd(queenWeight,  delta)
      case _ => ()
    // PST update
    pos.foreach { p =>
      val relR = if color == Color.White then p.row else 7 - p.row
      val index = relR * 8 + p.col
      atomicAdd(pstWeights(pieceType)(index), delta * 0.1) // smaller LR for positional
    }

  // General atomic addition for any AtomicReference 
  def updateGlobalWeight(ref: AtomicReference[Double], delta: Double): Unit =
    atomicAdd(ref, delta)
    
  // Export references for the trainer
  def getOpeningCenterPawn = openingCenterPawn
  def getOpeningCenterKnight = openingCenterKnight
  def getOpeningUndevelopedMinor = openingUndevelopedMinor
  def getOpeningPawnPushPenalty = openingPawnPushPenalty
  def getOpeningEarlyQueen = openingEarlyQueen
  def getOpeningCastledBonus = openingCastledBonus
  def getOpeningLostCastle = openingLostCastle
  def getOpeningUncastled = openingUncastled
  def getOpeningConnectedRooks = openingConnectedRooks
  def getOpeningWeakPawnF = openingWeakPawnF
  def getOpeningWeakPawnH = openingWeakPawnH
