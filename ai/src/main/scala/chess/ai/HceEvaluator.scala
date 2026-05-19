package chess.ai

import chess.model.{Color, GameState, MoveGenerator, Piece, PieceType, Pos}

class HceEvaluator(
  includePositionalHeuristics: Boolean = true
) :
  def evaluate(state: GameState): Double =
    val cp = HceEvaluator.taperedEvalCp(state, includePositionalHeuristics)
    val side = if state.activeColor == Color.White then 1.0 else -1.0
    // Unified scale: 1.0 == one pawn.
    math.max(-20.0, math.min(20.0, (cp * side) / 100.0))

object HceEvaluator:
  val default: HceEvaluator = new HceEvaluator()
  val fast: HceEvaluator = new HceEvaluator(includePositionalHeuristics = false)

  private val mgValue: Map[PieceType, Int] = Map(
    PieceType.Pawn -> 82,
    PieceType.Knight -> 337,
    PieceType.Bishop -> 365,
    PieceType.Rook -> 477,
    PieceType.Queen -> 1025,
    PieceType.King -> 0
  )

  private val egValue: Map[PieceType, Int] = Map(
    PieceType.Pawn -> 94,
    PieceType.Knight -> 281,
    PieceType.Bishop -> 297,
    PieceType.Rook -> 512,
    PieceType.Queen -> 936,
    PieceType.King -> 0
  )

  private val mgPhaseWeight: Map[PieceType, Int] = Map(
    PieceType.Pawn -> 0,
    PieceType.Knight -> 1,
    PieceType.Bishop -> 1,
    PieceType.Rook -> 2,
    PieceType.Queen -> 4,
    PieceType.King -> 0
  )

  private val TotalPhase = 24

  private val mgPassedPawnByRank: Array[Int] = Array(0, 8, 16, 28, 48, 82, 140, 0)
  private val egPassedPawnByRank: Array[Int] = Array(0, 12, 28, 55, 95, 155, 250, 0)

  private val mgPawnTable: Array[Int] = Array(
      0,   0,   0,   0,   0,   0,   0,   0,
     98, 134,  61,  95,  68, 126,  34, -11,
     -6,   7,  26,  31,  65,  56,  25, -20,
    -14,  13,   6,  21,  23,  12,  17, -23,
    -27,  -2,  -5,  12,  17,   6,  10, -25,
    -26,  -4,  -4, -10,   3,   3,  33, -12,
    -35,  -1, -20, -23, -15,  24,  38, -22,
      0,   0,   0,   0,   0,   0,   0,   0
  )

  private val egPawnTable: Array[Int] = Array(
      0,   0,   0,   0,   0,   0,   0,   0,
    178, 173, 158, 134, 147, 132, 165, 187,
     94, 100,  85,  67,  56,  53,  82,  84,
     32,  24,  13,   5,  -2,   4,  17,  17,
     13,   9,  -3,  -7,  -7,  -8,   3,  -1,
      4,   7,  -6,   1,   0,  -5,  -1,  -8,
     13,   8,   8,  10,  13,   0,   2,  -7,
      0,   0,   0,   0,   0,   0,   0,   0
  )

  private val mgKnightTable: Array[Int] = Array(
    -167, -89, -34, -49,  61, -97, -15, -107,
     -73, -41,  72,  36,  23,  62,   7,  -17,
     -47,  60,  37,  65,  84, 129,  73,   44,
      -9,  17,  19,  53,  37,  69,  18,   22,
     -13,   4,  16,  13,  28,  19,  21,   -8,
     -23,  -9,  12,  10,  19,  17,  25,  -16,
     -29, -53, -12,  -3,  -1,  18, -14,  -19,
    -105, -21, -58, -33, -17, -28, -19,  -23
  )

  private val egKnightTable: Array[Int] = Array(
    -58, -38, -13, -28, -31, -27, -63, -99,
    -25,  -8, -25,  -2,  -9, -25, -24, -52,
    -24, -20,  10,   9,  -1,  -9, -19, -41,
    -17,   3,  22,  22,  22,  11,   8, -18,
    -18,  -6,  16,  25,  16,  17,   4, -18,
    -23,  -3,  -1,  15,  10,  -3, -20, -22,
    -42, -20, -10,  -5,  -2, -20, -23, -44,
    -29, -51, -23, -15, -22, -18, -50, -64
  )

  private val mgBishopTable: Array[Int] = Array(
    -29,   4, -82, -37, -25, -42,   7,  -8,
    -26,  16, -18, -13,  30,  59,  18, -47,
    -16,  37,  43,  40,  35,  50,  37,  -2,
     -4,   5,  19,  50,  37,  37,   7,  -2,
     -6,  13,  13,  26,  34,  12,  10,   4,
      0,  15,  15,  15,  14,  27,  18,  10,
      4,  15,  16,   0,   7,  21,  33,   1,
    -33,  -3, -14, -21, -13, -12, -39, -21
  )

  private val egBishopTable: Array[Int] = Array(
    -14, -21, -11,  -8,  -7,  -9, -17, -24,
     -8,  -4,   7, -12,  -3, -13,  -4, -14,
      2,  -8,   0,  -1,  -2,   6,   0,   4,
     -3,   9,  12,   9,  14,  10,   3,   2,
     -6,   3,  13,  19,   7,  10,  -3,  -9,
    -12,  -3,   8,  10,  13,   3,  -7, -15,
    -14, -18,  -7,  -1,   4,  -9, -15, -27,
    -23,  -9, -23,  -5,  -9, -16,  -5, -17
  )

  private val mgRookTable: Array[Int] = Array(
     32,  42,  32,  51, 63,   9,  31,  43,
     27,  32,  58,  62, 80,  67,  26,  44,
     -5,  19,  26,  36, 17,  45,  61,  16,
    -24, -11,   7,  26, 24,  35,  -8, -20,
    -36, -26, -12,  -1,  9,  -7,   6, -23,
    -45, -25, -16, -17,  3,   0,  -5, -33,
    -44, -16, -20,  -9, -1,  11,  -6, -71,
    -19, -13,   1,  17, 16,   7, -37, -26
  )

  private val egRookTable: Array[Int] = Array(
     13, 10, 18, 15, 12,  12,   8,   5,
     11, 13, 13, 11, -3,   3,   8,   3,
      7,  7,  7,  5,  4,  -3,  -5,  -3,
      4,  3, 13,  1,  2,   1,  -1,   2,
      3,  5,  8,  4, -5,  -6,  -8, -11,
     -4,  0, -5, -1, -7, -12,  -8, -16,
     -6, -6,  0,  2, -9,  -9, -11,  -3,
     -9,  2,  3, -1, -5, -13,   4, -20
  )

  private val mgQueenTable: Array[Int] = Array(
    -28,   0,  29,  12,  59,  44,  43,  45,
    -24, -39,  -5,   1, -16,  57,  28,  54,
    -13, -17,   7,   8,  29,  56,  47,  57,
    -27, -27, -16, -16,  -1,  17,  -2,   1,
     -9, -26,  -9, -10,  -2,  -4,   3,  -3,
    -14,   2, -11,  -2,  -5,   2,  14,   5,
    -35,  -8,  11,   2,   8,  15,  -3,   1,
     -1, -18,  -9,  10, -15, -25, -31, -50
  )

  private val egQueenTable: Array[Int] = Array(
     -9,  22,  22,  27,  27,  19,  10,  20,
    -17,  20,  32,  41,  58,  25,  30,   0,
    -20,   6,   9,  49,  47,  35,  19,   9,
      3,  22,  24,  45,  57,  40,  57,  36,
    -18,  28,  19,  47,  31,  34,  39,  23,
    -16, -27,  15,   6,   9,  17,  10,   5,
    -22, -23, -30, -16, -16, -23, -36, -32,
    -33, -28, -22, -43,  -5, -32, -20, -41
  )

  private val mgKingTable: Array[Int] = Array(
    -65,  23,  16, -15, -56, -34,   2,  13,
     29,  -1, -20,  -7,  -8,  -4, -38, -29,
     -9,  24,   2, -16, -20,   6,  22, -22,
    -17, -20, -12, -27, -30, -25, -14, -36,
    -49,  -1, -27, -39, -46, -44, -33, -51,
    -14, -14, -22, -46, -44, -30, -15, -27,
      1,   7,  -8, -64, -43, -16,   9,   8,
    -15,  36,  12, -54,   8, -28,  24,  14
  )

  private val egKingTable: Array[Int] = Array(
    -74, -35, -18, -18, -11,  15,   4, -17,
    -12,  17,  14,  17,  17,  38,  23,  11,
     10,  17,  23,  15,  20,  45,  44,  13,
     -8,  22,  24,  27,  26,  33,  26,   3,
    -18,  -4,  21,  24,  27,  23,   9, -11,
    -19,  -3,  11,  21,  23,  16,   7,  -9,
    -27, -11,   4,  13,  14,   4,  -5, -17,
    -53, -34, -21, -11, -28, -14, -24, -43
  )

  private val mgTables: Map[PieceType, Array[Int]] = Map(
    PieceType.Pawn -> mgPawnTable,
    PieceType.Knight -> mgKnightTable,
    PieceType.Bishop -> mgBishopTable,
    PieceType.Rook -> mgRookTable,
    PieceType.Queen -> mgQueenTable,
    PieceType.King -> mgKingTable
  )

  private val egTables: Map[PieceType, Array[Int]] = Map(
    PieceType.Pawn -> egPawnTable,
    PieceType.Knight -> egKnightTable,
    PieceType.Bishop -> egBishopTable,
    PieceType.Rook -> egRookTable,
    PieceType.Queen -> egQueenTable,
    PieceType.King -> egKingTable
  )

  private def taperedEvalCp(state: GameState, includePositionalHeuristics: Boolean): Double =
    var mgScore = 0
    var egScore = 0
    var phase = 0
    state.board.foreachPiece { (pos, piece) =>
      val sign = if piece.color == Color.White then 1 else -1
      val sq = pstIndex(pos.row, pos.col, piece.color)
      val pt = piece.pieceType
      mgScore += sign * (mgValue(pt) + mgTables(pt)(sq))
      egScore += sign * (egValue(pt) + egTables(pt)(sq))
      phase += mgPhaseWeight(pt)
    }
    if includePositionalHeuristics then
      mgScore += positionalRulesCp(state, endgame = false)
      egScore += positionalRulesCp(state, endgame = true)
    val boundedPhase = phase.min(TotalPhase).max(0)
    ((mgScore.toDouble * boundedPhase) + (egScore.toDouble * (TotalPhase - boundedPhase))) / TotalPhase.toDouble

  // PeSTO tables are indexed as A8..H1 from White's perspective.
  private def pstIndex(row: Int, col: Int, color: Color): Int =
    val whiteView = (7 - row) * 8 + col
    if color == Color.White then whiteView else whiteView ^ 56

  private def positionalRulesCp(state: GameState, endgame: Boolean): Int =
    scoreForColor(state, Color.White, endgame) - scoreForColor(state, Color.Black, endgame)

  private def scoreForColor(state: GameState, color: Color, endgame: Boolean): Int =
    val ownBuffer = scala.collection.mutable.ListBuffer.empty[(Pos, Piece)]
    val oppBuffer = scala.collection.mutable.ListBuffer.empty[(Pos, Piece)]
    val ownPawnBuffer = scala.collection.mutable.ListBuffer.empty[Pos]
    val oppPawnBuffer = scala.collection.mutable.ListBuffer.empty[Pos]
    val ownByFileCounts = Array.fill(8)(0)
    val homeRow = if color == Color.White then 0 else 7
    val oppHomeRow = if color == Color.White then 7 else 0
    var ownDevelopedMinors = 0
    var oppDevelopedMinors = 0
    var ownBishopCount = 0

    state.board.foreachPiece { (pos, piece) =>
      if piece.color == color then
        ownBuffer += pos -> piece
        piece.pieceType match
          case PieceType.Pawn =>
            ownPawnBuffer += pos
            ownByFileCounts(pos.col) += 1
          case PieceType.Bishop =>
            ownBishopCount += 1
            if pos.row != homeRow then ownDevelopedMinors += 1
          case PieceType.Knight =>
            if pos.row != homeRow then ownDevelopedMinors += 1
          case _ => ()
      else
        oppBuffer += pos -> piece
        piece.pieceType match
          case PieceType.Pawn =>
            oppPawnBuffer += pos
          case PieceType.Bishop | PieceType.Knight =>
            if pos.row != oppHomeRow then oppDevelopedMinors += 1
          case _ => ()
    }

    val own = ownBuffer.toList
    val opp = oppBuffer.toList
    val ownPawns = ownPawnBuffer.toList
    val oppPawns = oppPawnBuffer.toList
    val ownPawnSet = ownPawns.toSet

    var score = 0

    ownPawns.foreach { p =>
      if isConnectedPawn(p, ownPawnSet, color) then score += (if endgame then 8 else 10)
      if isPassedPawn(p, color, oppPawns) then score += passedPawnBonus(state, p, color, own, opp, endgame)
    }
    var file = 0
    while file < 8 do
      val cnt = ownByFileCounts(file)
      if cnt > 1 then score -= (cnt - 1) * (if endgame then 10 else 14)
      file += 1

    score += developmentScore(own, color, endgame)
    score += earlyQueenPenalty(own, color, ownDevelopedMinors)
    score += openingCoordinationScore(state, own, color, endgame, ownDevelopedMinors)
    score += kingSafetyScore(state, own, opp, ownPawns, oppPawns, color, endgame)
    score += initiativePressureScore(state, own, opp, ownPawns, oppPawns, color, endgame, ownDevelopedMinors, oppDevelopedMinors)
    score += tacticalVulnerabilityScore(state, own, opp, color)
    score += endgameKingActivityScore(state, own, opp, ownPawns, oppPawns, color, endgame)
    score += pawnRaceScore(state, own, opp, ownPawns, ownPawnSet, oppPawns, color, endgame)

    if ownBishopCount >= 2 then score += 22
    score += rookFileActivity(own, ownByFileCounts, oppPawns, color, endgame)
    score += rookEndgameScore(own, opp, ownPawns, oppPawns, color, endgame)

    score

  private def isConnectedPawn(p: chess.model.Pos, ownPawns: Set[chess.model.Pos], color: Color): Boolean =
    val forward = if color == Color.White then 1 else -1
    ownPawns.contains(chess.model.Pos(p.col - 1, p.row)) ||
    ownPawns.contains(chess.model.Pos(p.col + 1, p.row)) ||
    ownPawns.contains(chess.model.Pos(p.col - 1, p.row - forward)) ||
    ownPawns.contains(chess.model.Pos(p.col + 1, p.row - forward))

  private def isPassedPawn(p: chess.model.Pos, color: Color, oppPawns: List[chess.model.Pos]): Boolean =
    oppPawns.forall { op =>
      val sameOrAdjacentFile = math.abs(op.col - p.col) <= 1
      val ahead = if color == Color.White then op.row > p.row else op.row < p.row
      !(sameOrAdjacentFile && ahead)
    }

  private def passedPawnBonus(
    state: GameState,
    p: chess.model.Pos,
    color: Color,
    own: List[(chess.model.Pos, Piece)],
    opp: List[(chess.model.Pos, Piece)],
    endgame: Boolean
  ): Int =
    val rankFromStart = if color == Color.White then p.row else 7 - p.row
    val table = if endgame then egPassedPawnByRank else mgPassedPawnByRank
    val forward = if color == Color.White then 1 else -1
    val oneStep = chess.model.Pos(p.col, p.row + forward)
    val blocked = oneStep.isValid && state.board.isOccupied(oneStep)
    val pawnSupported = own.exists {
      case (op, Piece(_, PieceType.Pawn)) =>
        math.abs(op.col - p.col) == 1 && op.row == p.row - forward
      case _ => false
    }
    val promotion = chess.model.Pos(p.col, if color == Color.White then 7 else 0)
    val oppKingDistance = opp.collectFirst {
      case (kp, Piece(_, PieceType.King)) => chebyshevDistance(kp, promotion)
    }.getOrElse(0)
    val promotionDistance = 7 - rankFromStart
    val outsideKingSquareBonus =
      if endgame && rankFromStart >= 4 && oppKingDistance > promotionDistance + 1 then 45
      else 0
    val supportBonus =
      if pawnSupported then (if endgame then 24 else 14) else 0
    val blockedPenalty =
      if blocked then (if endgame then 70 else 35) else 0

    (table(rankFromStart) + supportBonus + outsideKingSquareBonus - blockedPenalty).max(0)

  private def chebyshevDistance(a: chess.model.Pos, b: chess.model.Pos): Int =
    math.max(math.abs(a.col - b.col), math.abs(a.row - b.row))

  private def promotionSquare(pawn: Pos, color: Color): Pos =
    Pos(pawn.col, if color == Color.White then 7 else 0)

  private def stopSquare(pawn: Pos, color: Color): Pos =
    val forward = if color == Color.White then 1 else -1
    Pos(pawn.col, (pawn.row + forward).max(0).min(7))

  private def isBehindPawn(piece: Pos, pawn: Pos, pawnColor: Color): Boolean =
    piece.col == pawn.col && (if pawnColor == Color.White then piece.row < pawn.row else piece.row > pawn.row)

  private def centrality(pos: Pos): Int =
    val fileDistance = math.min(math.abs(pos.col - 3), math.abs(pos.col - 4))
    val rankDistance = math.min(math.abs(pos.row - 3), math.abs(pos.row - 4))
    6 - (fileDistance + rankDistance)

  private def endgameKingActivityScore(
    state: GameState,
    own: List[(Pos, Piece)],
    opp: List[(Pos, Piece)],
    ownPawns: List[Pos],
    oppPawns: List[Pos],
    color: Color,
    endgame: Boolean
  ): Int =
    if !endgame then 0
    else
      val ownKingOpt = own.collectFirst { case (p, Piece(_, PieceType.King)) => p }
      val oppKingOpt = opp.collectFirst { case (p, Piece(_, PieceType.King)) => p }
      (ownKingOpt, oppKingOpt) match
        case (Some(ownKing), Some(_)) =>
          val ownPassers = ownPawns.filter(isPassedPawn(_, color, oppPawns))
          val oppPassers = oppPawns.filter(isPassedPawn(_, color.opposite, ownPawns))
          val center = centrality(ownKing) * 5
          val supportOwnPassers = ownPassers.map { pawn =>
            val target = stopSquare(pawn, color)
            (7 - chebyshevDistance(ownKing, target)).max(0) * 7
          }.sum
          val stopEnemyPassers = oppPassers.map { pawn =>
            val target = stopSquare(pawn, color.opposite)
            (7 - chebyshevDistance(ownKing, target)).max(0) * 8
          }.sum
          center + supportOwnPassers + stopEnemyPassers
        case _ => 0

  private def pawnRaceScore(
    state: GameState,
    own: List[(Pos, Piece)],
    opp: List[(Pos, Piece)],
    ownPawns: List[Pos],
    ownPawnSet: Set[Pos],
    oppPawns: List[Pos],
    color: Color,
    endgame: Boolean
  ): Int =
    if !endgame then 0
    else
      val oppKingOpt = opp.collectFirst { case (p, Piece(_, PieceType.King)) => p }
      val ownKingOpt = own.collectFirst { case (p, Piece(_, PieceType.King)) => p }
      (ownKingOpt, oppKingOpt) match
        case (Some(ownKing), Some(oppKing)) =>
          ownPawns.filter(isPassedPawn(_, color, oppPawns)).map { pawn =>
            val rankFromStart = if color == Color.White then pawn.row else 7 - pawn.row
            val promotion = promotionSquare(pawn, color)
            val pawnMoves = 7 - rankFromStart
            val opponentTooLate = chebyshevDistance(oppKing, promotion) > pawnMoves + 1
            val ownKingSupports = chebyshevDistance(ownKing, stopSquare(pawn, color)) <= 2
            val connected = isConnectedPawn(pawn, ownPawnSet, color)
            (if opponentTooLate then 85 else 0) +
              (if ownKingSupports then 28 else 0) +
              (if connected && rankFromStart >= 4 then 34 else 0)
          }.sum
        case _ => 0

  private def developmentScore(own: List[(chess.model.Pos, Piece)], color: Color, endgame: Boolean): Int =
    if endgame then 0
    else
      val homeRow = if color == Color.White then 0 else 7
      own.foldLeft(0) { case (acc, (p, piece)) =>
        piece.pieceType match
          case PieceType.Knight | PieceType.Bishop => if p.row != homeRow then acc + 16 else acc
          case PieceType.Rook =>
            val onStartSquare = (p.row == homeRow) && (p.col == 0 || p.col == 7)
            if onStartSquare then acc else acc - 6
          case _ => acc
      }

  private def developedMinorCount(own: List[(Pos, Piece)], color: Color): Int =
    val homeRow = if color == Color.White then 0 else 7
    own.count {
      case (p, Piece(_, PieceType.Knight | PieceType.Bishop)) => p.row != homeRow
      case _ => false
    }

  private def earlyQueenPenalty(own: List[(chess.model.Pos, Piece)], color: Color, ownDevelopedMinors: Int): Int =
    val queenStart = if color == Color.White then chess.model.Pos(3, 0) else chess.model.Pos(3, 7)
    own.find(_._2.pieceType == PieceType.Queen) match
      case Some((qPos, _)) if qPos != queenStart && ownDevelopedMinors <= 2 => -36
      case Some((qPos, _)) if qPos != queenStart && ownDevelopedMinors == 3 => -18
      case _ => 0

  private def openingCoordinationScore(
    state: GameState,
    own: List[(Pos, Piece)],
    color: Color,
    endgame: Boolean,
    ownDevelopedMinors: Int
  ): Int =
    if endgame then 0
    else
      val homeRow = if color == Color.White then 0 else 7
      val rooks = own.collect { case (p, Piece(_, PieceType.Rook)) => p }
      val kingPos = own.collectFirst { case (p, Piece(_, PieceType.King)) => p }.getOrElse(Pos(4, homeRow))
      def isCastledRook(p: Pos): Boolean =
        (kingPos == Pos(6, homeRow) && p == Pos(5, homeRow)) ||
          (kingPos == Pos(2, homeRow) && p == Pos(3, homeRow))
      val rooksMovedEarlyPenalty =
        if ownDevelopedMinors <= 3 then
          rooks.count(p => !(p.row == homeRow && (p.col == 0 || p.col == 7)) && !isCastledRook(p)) * 55
        else 0
      val kingCentralPenalty =
        if kingPos.row == homeRow && kingPos.col >= 3 && kingPos.col <= 5 && ownDevelopedMinors <= 3 then 45
        else 0
      val castlingRights = state.castlingRights
      val hasKingSideRight = if color == Color.White then castlingRights.whiteKingSide else castlingRights.blackKingSide
      val hasQueenSideRight = if color == Color.White then castlingRights.whiteQueenSide else castlingRights.blackQueenSide
      val lostKingSidePenalty =
        if !hasKingSideRight && kingPos == Pos(4, homeRow) && ownDevelopedMinors <= 3 then 55 else 0
      val lostCastlingPenalty =
        if !hasKingSideRight && !hasQueenSideRight && ownDevelopedMinors <= 3 then 80 else 0
      -(rooksMovedEarlyPenalty + kingCentralPenalty + lostKingSidePenalty + lostCastlingPenalty)

  private def kingSafetyScore(
    state: GameState,
    own: List[(Pos, Piece)],
    opp: List[(Pos, Piece)],
    ownPawns: List[Pos],
    oppPawns: List[Pos],
    color: Color,
    endgame: Boolean
  ): Int =
    val kingPosOpt = own.collectFirst { case (p, Piece(_, PieceType.King)) => p }
    if kingPosOpt.isEmpty then return 0
    val kingPos = kingPosOpt.get
    val rights = state.castlingRights
    val hasKingSideRight = if color == Color.White then rights.whiteKingSide else rights.blackKingSide
    val hasQueenSideRight = if color == Color.White then rights.whiteQueenSide else rights.blackQueenSide
    val row = if color == Color.White then 0 else 7
    val pawnShieldRow = if color == Color.White then 1 else 6

    var s = 0
    val castled = kingPos == chess.model.Pos(6, row) || kingPos == chess.model.Pos(2, row)
    if castled then s += (if endgame then 10 else 70)
    else
      val centralKing = kingPos.row == row && kingPos.col >= 3 && kingPos.col <= 5
      if !endgame && centralKing && !hasKingSideRight then s -= 60
      if !hasKingSideRight && !hasQueenSideRight then s -= (if endgame then 10 else 80)
      else if hasKingSideRight || hasQueenSideRight then s += (if endgame then 0 else 12)

    if !endgame then
      val shieldSquares =
        if kingPos.col >= 5 then List(chess.model.Pos(5, pawnShieldRow), chess.model.Pos(6, pawnShieldRow), chess.model.Pos(7, pawnShieldRow))
        else if kingPos.col <= 2 then List(chess.model.Pos(0, pawnShieldRow), chess.model.Pos(1, pawnShieldRow), chess.model.Pos(2, pawnShieldRow))
        else Nil
      val ownPawnSet = ownPawns.toSet
      shieldSquares.foreach { sq =>
        if ownPawnSet.contains(sq) then s += 12 else s -= 24
      }
      s -= advancedShieldPawnExposure(kingPos, ownPawns, color)
      s -= kingDangerScore(state, own, opp, ownPawns, oppPawns, color)
    s

  private def initiativePressureScore(
    state: GameState,
    own: List[(Pos, Piece)],
    opp: List[(Pos, Piece)],
    ownPawns: List[Pos],
    oppPawns: List[Pos],
    color: Color,
    endgame: Boolean,
    ownDevelopedMinors: Int,
    oppDevelopedMinors: Int
  ): Int =
    if endgame then 0
    else
      val oppKingPosOpt = opp.collectFirst { case (p, Piece(_, PieceType.King)) => p }
      if oppKingPosOpt.isEmpty then 0
      else
        val developmentLead = (ownDevelopedMinors - oppDevelopedMinors).max(0)
        val initiativeBase = kingDangerScore(state, opp, own, oppPawns, ownPawns, color.opposite) / 2
        initiativeBase + (developmentLead * 8)

  private val KingZoneDirections: Array[(Int, Int)] =
    Array((1, 1), (1, -1), (-1, 1), (-1, -1), (1, 0), (-1, 0), (0, 1), (0, -1))

  private def kingDangerScore(
    state: GameState,
    own: List[(Pos, Piece)],
    enemy: List[(Pos, Piece)],
    ownPawns: List[Pos],
    enemyPawns: List[Pos],
    color: Color
  ): Int =
    val kingOpt = own.collectFirst { case (p, Piece(_, PieceType.King)) => p }
    if kingOpt.isEmpty then 0
    else
      val king = kingOpt.get
      val zone = kingZone(king)
      val zoneSet = zone.toSet
      val attackUnits = enemyAttackUnits(state, enemy, zoneSet, king)
      val linePressure = kingFilePressureScore(king, ownPawns, enemyPawns, enemy, color)
      val directHeavyPressure = enemyHeavyPiecePressure(king, enemy)
      val knightPressure = enemyKnightOutpostPressure(state, king, ownPawns, enemy, color)
      val missingDefenders = missingKingDefendersPenalty(state, own, zoneSet, king)
      val sacrificePressure = nearKingSacrificePressure(state, enemy, zoneSet, king, color.opposite)
      attackUnits + linePressure + directHeavyPressure + knightPressure + missingDefenders + sacrificePressure

  private def kingZone(king: Pos): List[Pos] =
    val out = scala.collection.mutable.ListBuffer(king)
    var i = 0
    while i < KingZoneDirections.length do
      val (dc, dr) = KingZoneDirections(i)
      val p = Pos(king.col + dc, king.row + dr)
      if p.isValid then out += p
      i += 1
    out.toList

  private def enemyAttackUnits(
    state: GameState,
    enemy: List[(Pos, Piece)],
    zone: Set[Pos],
    king: Pos
  ): Int =
    enemy.map { case (pos, piece) =>
      val hits = zone.count(square => pieceAttacksSquare(state, pos, piece, square))
      if hits == 0 then 0
      else
        val weight = piece.pieceType match
          case PieceType.Queen => 28
          case PieceType.Rook => 20
          case PieceType.Bishop => 15
          case PieceType.Knight => 17
          case PieceType.Pawn => 7
          case PieceType.King => 0
        val closeBonus =
          if piece.pieceType != PieceType.Pawn && chebyshevDistance(pos, king) <= 2 then 12 else 0
        val checkLineBonus =
          if canGiveNearKingCheck(state, pos, piece, king) then 18 else 0
        (hits * weight) + closeBonus + checkLineBonus
    }.sum

  private def missingKingDefendersPenalty(
    state: GameState,
    own: List[(Pos, Piece)],
    zone: Set[Pos],
    king: Pos
  ): Int =
    val defenders = own.count {
      case (pos, Piece(_, PieceType.King)) => false
      case (pos, piece) =>
        !isPinnedToKing(state, pos, piece, king) &&
          (chebyshevDistance(pos, king) <= 2 || zone.exists(square => pieceAttacksSquare(state, pos, piece, square)))
    }
    val pawnDefenders = own.count {
      case (pos, Piece(_, PieceType.Pawn)) => chebyshevDistance(pos, king) <= 2
      case _ => false
    }
    val expected = if pawnDefenders >= 2 then 3 else 4
    (expected - defenders).max(0) * 18

  private def tacticalVulnerabilityScore(
    state: GameState,
    own: List[(Pos, Piece)],
    opp: List[(Pos, Piece)],
    color: Color
  ): Int =
    val ownKing = own.collectFirst { case (p, Piece(_, PieceType.King)) => p }
    val oppKing = opp.collectFirst { case (p, Piece(_, PieceType.King)) => p }
    val ownPenalty = own.map {
      case (pos, piece) if piece.pieceType != PieceType.King =>
        pieceVulnerability(state, pos, piece, color.opposite, own, ownKing, reward = false)
      case _ => 0
    }.sum
    val oppBonus = opp.map {
      case (pos, piece) if piece.pieceType != PieceType.King =>
        pieceVulnerability(state, pos, piece, color, opp, oppKing, reward = true)
      case _ => 0
    }.sum
    oppBonus - ownPenalty

  private def pieceVulnerability(
    state: GameState,
    pos: Pos,
    piece: Piece,
    attackerColor: Color,
    defenders: List[(Pos, Piece)],
    defenderKing: Option[Pos],
    reward: Boolean
  ): Int =
    val attacked = MoveGenerator.isAttackedBy(state.board, pos, attackerColor)
    val reliablyDefended = hasReliableDefender(state, pos, defenders, defenderKing)
    val pieceValue = tacticalPieceValue(piece.pieceType)
    val loose =
      if attacked && !reliablyDefended then
        val base = if reward then 14 else 18
        val divisor = if reward then 7 else 5
        base + (pieceValue / divisor).min(if reward then 120 else 150)
      else 0
    val lowAttacker =
      lowestAttackerValue(state, pos, attackerColor) match
        case Some(attackerValue) if attackerValue < pieceValue =>
          val base = if reward then 10 else 12
          val divisor = if reward then 12 else 10
          base + ((pieceValue - attackerValue) / divisor).min(if reward then 70 else 85)
        case _ => 0
    val pin =
      if defenderKing.exists(isPinnedToKing(state, pos, piece, _)) then
        piece.pieceType match
          case PieceType.Queen => if reward then 40 else 55
          case PieceType.Rook => if reward then 28 else 36
          case PieceType.Bishop | PieceType.Knight => if reward then 20 else 28
          case PieceType.Pawn => if reward then 8 else 10
          case PieceType.King => 0
      else 0
    loose + lowAttacker + pin

  private def hasReliableDefender(
    state: GameState,
    target: Pos,
    defenders: List[(Pos, Piece)],
    defenderKing: Option[Pos]
  ): Boolean =
    defenders.exists {
      case (pos, Piece(_, PieceType.King)) =>
        pos != target && chebyshevDistance(pos, target) <= 1
      case (pos, piece) =>
        pos != target &&
          !defenderKing.exists(isPinnedToKing(state, pos, piece, _)) &&
          pieceAttacksSquare(state, pos, piece, target)
    }

  private def lowestAttackerValue(state: GameState, target: Pos, attackerColor: Color): Option[Int] =
    var best = Int.MaxValue
    state.board.foreachPieceOf(attackerColor) { (pos, piece) =>
      if pos != target && pieceAttacksSquare(state, pos, piece, target) then
        best = best.min(tacticalPieceValue(piece.pieceType))
    }
    if best == Int.MaxValue then None else Some(best)

  private def isPinnedToKing(state: GameState, pos: Pos, piece: Piece, king: Pos): Boolean =
    if piece.pieceType == PieceType.King || !sliderCompatible(PieceType.Queen, pos, king) then false
    else
      val dc = Integer.signum(pos.col - king.col)
      val dr = Integer.signum(pos.row - king.row)
      var cur = Pos(king.col + dc, king.row + dr)
      var reachedPiece = false
      var pinned = false
      var done = false
      while cur.isValid && !done do
        if cur == pos then reachedPiece = true
        else if state.board.isOccupied(cur) then
          if !reachedPiece then done = true
          else
            pinned = state.board.get(cur).exists { attacker =>
              attacker.color == piece.color.opposite && sliderAttacksAlong(attacker.pieceType, dc, dr)
            }
            done = true
        cur = Pos(cur.col + dc, cur.row + dr)
      pinned

  private def sliderAttacksAlong(pieceType: PieceType, dc: Int, dr: Int): Boolean =
    val diagonal = dc != 0 && dr != 0
    pieceType match
      case PieceType.Queen => true
      case PieceType.Bishop => diagonal
      case PieceType.Rook => !diagonal
      case _ => false

  private def tacticalPieceValue(pieceType: PieceType): Int =
    pieceType match
      case PieceType.Pawn => 100
      case PieceType.Knight => 320
      case PieceType.Bishop => 330
      case PieceType.Rook => 500
      case PieceType.Queen => 900
      case PieceType.King => 20000

  private def nearKingSacrificePressure(
    state: GameState,
    enemy: List[(Pos, Piece)],
    zone: Set[Pos],
    king: Pos,
    attackerColor: Color
  ): Int =
    enemy.map {
      case (pos, piece @ Piece(_, PieceType.Knight | PieceType.Bishop | PieceType.Rook | PieceType.Queen)) =>
        val hitsZone = zone.exists(square => pieceAttacksSquare(state, pos, piece, square))
        val protectedAttacker = MoveGenerator.isAttackedBy(state.board, pos, attackerColor)
        if hitsZone && protectedAttacker && chebyshevDistance(pos, king) <= 3 then
          piece.pieceType match
            case PieceType.Queen => 16
            case PieceType.Rook => 18
            case PieceType.Bishop | PieceType.Knight => 22
            case _ => 0
        else 0
      case _ => 0
    }.sum

  private def canGiveNearKingCheck(
    state: GameState,
    pos: Pos,
    piece: Piece,
    king: Pos
  ): Boolean =
    piece.pieceType match
      case PieceType.Queen | PieceType.Rook | PieceType.Bishop =>
        sliderCompatible(piece.pieceType, pos, king) && blockersBetween(state, pos, king) <= 1
      case PieceType.Knight =>
        knightAttacks(pos).exists(t => chebyshevDistance(t, king) <= 1)
      case _ => false

  private def pieceAttacksSquare(state: GameState, from: Pos, piece: Piece, target: Pos): Boolean =
    piece.pieceType match
      case PieceType.Pawn =>
        val dir = if piece.color == Color.White then 1 else -1
        target.row - from.row == dir && math.abs(target.col - from.col) == 1
      case PieceType.Knight =>
        val dc = math.abs(target.col - from.col)
        val dr = math.abs(target.row - from.row)
        (dc == 1 && dr == 2) || (dc == 2 && dr == 1)
      case PieceType.Bishop =>
        math.abs(target.col - from.col) == math.abs(target.row - from.row) &&
          blockersBetween(state, from, target) == 0
      case PieceType.Rook =>
        (target.col == from.col || target.row == from.row) &&
          blockersBetween(state, from, target) == 0
      case PieceType.Queen =>
        sliderCompatible(PieceType.Queen, from, target) &&
          blockersBetween(state, from, target) == 0
      case PieceType.King =>
        chebyshevDistance(from, target) <= 1

  private def sliderCompatible(pieceType: PieceType, from: Pos, target: Pos): Boolean =
    pieceType match
      case PieceType.Bishop => math.abs(target.col - from.col) == math.abs(target.row - from.row)
      case PieceType.Rook => target.col == from.col || target.row == from.row
      case PieceType.Queen =>
        target.col == from.col ||
          target.row == from.row ||
          math.abs(target.col - from.col) == math.abs(target.row - from.row)
      case _ => false

  private def blockersBetween(state: GameState, from: Pos, target: Pos): Int =
    val dc = Integer.signum(target.col - from.col)
    val dr = Integer.signum(target.row - from.row)
    var cur = Pos(from.col + dc, from.row + dr)
    var blockers = 0
    while cur.isValid && cur != target do
      if state.board.isOccupied(cur) then blockers += 1
      cur = Pos(cur.col + dc, cur.row + dr)
    blockers

  private def kingFilePressureScore(
    kingPos: Pos,
    ownPawns: List[Pos],
    oppPawns: List[Pos],
    enemyPieces: List[(Pos, Piece)],
    color: Color
  ): Int =
    val files = ((kingPos.col - 1) to (kingPos.col + 1)).filter(f => f >= 0 && f <= 7)
    files.map { file =>
      val ownPawnOnFile = ownPawns.exists(_.col == file)
      val oppPawnOnFile = oppPawns.exists(_.col == file)
      val heavyOnFile = enemyPieces.count {
        case (p, Piece(_, PieceType.Rook | PieceType.Queen)) => p.col == file
        case _ => false
      }
      val closeHeavy = enemyPieces.exists {
        case (p, Piece(_, PieceType.Rook | PieceType.Queen)) =>
          p.col == file &&
          math.abs(p.row - kingPos.row) <= 4 &&
          (if color == Color.White then p.row >= kingPos.row else p.row <= kingPos.row)
        case _ => false
      }
      val opennessPenalty =
        if !ownPawnOnFile && !oppPawnOnFile then 10
        else if !ownPawnOnFile then 6
        else 0
      val heavyPenalty = heavyOnFile * (if closeHeavy then 12 else 8)
      opennessPenalty + heavyPenalty
    }.sum

  private def advancedShieldPawnExposure(kingPos: Pos, ownPawns: List[Pos], color: Color): Int =
    if kingPos.col < 5 then 0
    else
      val homeShieldRow = if color == Color.White then 1 else 6
      val advancedRows =
        if color == Color.White then Set(2, 3, 4)
        else Set(5, 4, 3)
      val shieldFiles = List(5, 6, 7)
      shieldFiles.map { file =>
        val hasHomePawn = ownPawns.exists(p => p.col == file && p.row == homeShieldRow)
        val advancedPawn = ownPawns.exists(p => p.col == file && advancedRows.contains(p.row))
        if !hasHomePawn && advancedPawn then 14
        else if !hasHomePawn then 8
        else 0
      }.sum

  private def enemyHeavyPiecePressure(kingPos: Pos, enemyPieces: List[(Pos, Piece)]): Int =
    enemyPieces.map {
      case (p, Piece(_, PieceType.Queen)) =>
        val d = chebyshevDistance(p, kingPos)
        if d <= 2 then 26
        else if d <= 4 then 14
        else 0
      case (p, Piece(_, PieceType.Rook)) =>
        val d = chebyshevDistance(p, kingPos)
        if d <= 2 then 18
        else if d <= 4 then 10
        else 0
      case _ => 0
    }.sum

  private def enemyKnightOutpostPressure(
    state: GameState,
    kingPos: Pos,
    ownPawns: List[Pos],
    enemyPieces: List[(Pos, Piece)],
    color: Color
  ): Int =
    enemyPieces.map {
      case (p, Piece(_, PieceType.Knight)) =>
        val attacksZone = knightAttacks(p).exists(t => chebyshevDistance(t, kingPos) <= 1)
        val supportedByPawn = enemyPawnSupportsSquare(state, p, color.opposite)
        val hardToChallengeByPawn =
          !ownPawns.exists { ownPawn =>
            ownPawn.col == p.col - 1 || ownPawn.col == p.col + 1
          }
        if attacksZone && supportedByPawn && hardToChallengeByPawn then 28
        else if attacksZone && supportedByPawn then 24
        else if attacksZone then 16
        else if chebyshevDistance(p, kingPos) <= 2 then 10
        else 0
      case _ => 0
    }.sum

  private def enemyPawnSupportsSquare(state: GameState, target: Pos, pawnColor: Color): Boolean =
    val supportRowDelta = if pawnColor == Color.White then -1 else 1
    List(
      Pos(target.col - 1, target.row + supportRowDelta),
      Pos(target.col + 1, target.row + supportRowDelta)
    ).exists(pos =>
      pos.isValid && state.board.get(pos).contains(Piece(pawnColor, PieceType.Pawn))
    )

  private def knightAttacks(pos: Pos): List[Pos] =
    List(
      Pos(pos.col + 2, pos.row + 1),
      Pos(pos.col + 2, pos.row - 1),
      Pos(pos.col - 2, pos.row + 1),
      Pos(pos.col - 2, pos.row - 1),
      Pos(pos.col + 1, pos.row + 2),
      Pos(pos.col + 1, pos.row - 2),
      Pos(pos.col - 1, pos.row + 2),
      Pos(pos.col - 1, pos.row - 2)
    ).filter(_.isValid)

  private def rookFileActivity(
    own: List[(chess.model.Pos, Piece)],
    ownByFile: Array[Int],
    oppPawns: List[chess.model.Pos],
    color: Color,
    endgame: Boolean
  ): Int =
    val rooks = own.collect { case (p, Piece(_, PieceType.Rook)) => p }
    rooks.foldLeft(0) { (acc, r) =>
      val ownPawnOnFile = ownByFile(r.col) > 0
      val oppPawnOnFile = oppPawns.exists(_.col == r.col)
      val fileBonus =
        if !ownPawnOnFile && !oppPawnOnFile then (if endgame then 14 else 10)
        else if !ownPawnOnFile then (if endgame then 8 else 6)
        else 0
      val targetRank = if color == Color.White then 6 else 1
      val rankBonus = if r.row == targetRank then 8 else 0
      acc + fileBonus + rankBonus
    }

  private def rookEndgameScore(
    own: List[(Pos, Piece)],
    opp: List[(Pos, Piece)],
    ownPawns: List[Pos],
    oppPawns: List[Pos],
    color: Color,
    endgame: Boolean
  ): Int =
    if !endgame then 0
    else
      val rooks = own.collect { case (p, Piece(_, PieceType.Rook)) => p }
      if rooks.isEmpty then 0
      else
        val ownPassers = ownPawns.filter(isPassedPawn(_, color, oppPawns))
        val oppPassers = oppPawns.filter(isPassedPawn(_, color.opposite, ownPawns))
        val oppKingOpt = opp.collectFirst { case (p, Piece(_, PieceType.King)) => p }
        rooks.map { rook =>
          val behindOwn = ownPassers.count(pawn => isBehindPawn(rook, pawn, color)) * 38
          val behindEnemy = oppPassers.count(pawn => isBehindPawn(rook, pawn, color.opposite)) * 32
          val sideOrBackChecks =
            oppKingOpt.map { king =>
              val sameRank = rook.row == king.row
              val sameFile = rook.col == king.col
              if sameRank || sameFile then 12 else 0
            }.getOrElse(0)
          val passivePenalty =
            if ownPassers.nonEmpty && !ownPassers.exists(pawn => isBehindPawn(rook, pawn, color)) then 10 else 0
          behindOwn + behindEnemy + sideOrBackChecks - passivePenalty
        }.sum
