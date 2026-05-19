package chess.ai

import cats.effect.unsafe.implicits.global
import chess.model.*
import chess.persistence.PersistenceModule
import chess.util.parser.CoordinateMoveParser
import java.util.SplittableRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicLong, AtomicReferenceArray}

import scala.util.Try
import scala.util.boundary

/**
 * Minimal Alpha-Beta engine with:
 * - opening DB shortcut
 * - HCE static evaluation
 * - heuristic move ordering
 */
object AlphaBetaAgent:

  final case class PonderResult(
    expectedOpponentMove: Move,
    expectedFen: String,
    plannedReply: Option[Move],
    depth: Int,
    warmedNodes: Long
  )

  private enum SearchMode:
    case Search, PonderPredict, PonderReply

  private val MATE_SCORE = 1_000_000
  private val ScoreCap = MATE_SCORE.toDouble
  private val MateScoreThreshold = MATE_SCORE.toDouble - 10_000.0
  private val ValueScale = 1.0
  private val MaxSearchDepth = 64
  private val MaxQuiescenceDepth =
    sys.env.get("CHESS_QS_MAX_DEPTH").flatMap(_.toIntOption).getOrElse(3).max(0)
  private val QuiescenceChecksDepth =
    sys.env.get("CHESS_QS_CHECK_DEPTH").flatMap(_.toIntOption).getOrElse(1).max(0)
  private val NullMoveReduction = 2
  private val LmrReduction = 1
  private val ReverseFutilityMaxDepth = 3
  private val RazoringMaxDepth = 2
  private val FutilityMaxDepth = 2
  private val LateMovePruningMaxDepth = 3
  private val SeeQuietPruningMaxDepth = 2
  private val TacticalOrderingMinDepth =
    sys.env.get("CHESS_TACTICAL_ORDERING_MIN_DEPTH").flatMap(_.toIntOption).getOrElse(3).max(0)
  private val DefensiveOrderingMinDepth =
    sys.env.get("CHESS_DEFENSIVE_ORDERING_MIN_DEPTH").flatMap(_.toIntOption).getOrElse(3).max(0)
  private val PvsNullWindow = 0.0001
  private val AspirationInitialWindow = 1.2
  private val DepthStartMinDepth =
    sys.env.get("CHESS_DEPTH_START_MIN_DEPTH").flatMap(_.toIntOption).getOrElse(7).max(1)
  private val DepthStartSafetyMultiplier =
    sys.env.get("CHESS_DEPTH_START_SAFETY").flatMap(_.toDoubleOption).getOrElse(1.35).max(1.0)
  private val DepthStartFallbackGrowth =
    sys.env.get("CHESS_DEPTH_START_FALLBACK_GROWTH").flatMap(_.toDoubleOption).getOrElse(3.0).max(1.1)
  private val DepthStartMinGrowth =
    sys.env.get("CHESS_DEPTH_START_MIN_GROWTH").flatMap(_.toDoubleOption).getOrElse(1.6).max(1.0)
  private val DepthStartMaxGrowth =
    sys.env.get("CHESS_DEPTH_START_MAX_GROWTH").flatMap(_.toDoubleOption).getOrElse(5.0).max(DepthStartMinGrowth)
  private val DepthStartReserveMs =
    sys.env.get("CHESS_DEPTH_START_RESERVE_MS").flatMap(_.toLongOption).getOrElse(15L).max(0L)
  private val MaxOpeningBookPly =
    sys.env.get("CHESS_OPENING_MAX_PLY").flatMap(_.toIntOption).getOrElse(16)
  private val StalemateAvoidMaterialCp = 300
  private val RootDrawPolicyThreshold = 0.7
  private val RootDrawPolicyBias = 0.35
  private val OpeningValidationMinGainCp = 300
  private val OpeningValidationEvalSlack = 0.35
  private val FastHceAtOrBelowDepth =
    sys.env.get("CHESS_FAST_HCE_DEPTH").flatMap(_.toIntOption).getOrElse(-1)
  private val PonderTotalCapMs =
    sys.env.get("CHESS_PONDER_TOTAL_CAP_MS").flatMap(_.toLongOption).filter(_ > 0L)
  private val PonderPredictMinMs =
    sys.env.get("CHESS_PONDER_PREDICT_MIN_MS").flatMap(_.toLongOption).getOrElse(80L).max(1L)
  private val PonderPredictMaxMs =
    sys.env.get("CHESS_PONDER_PREDICT_MAX_MS").flatMap(_.toLongOption).getOrElse(350L).max(PonderPredictMinMs)
  private val PonderPredictFraction =
    sys.env.get("CHESS_PONDER_PREDICT_FRACTION").flatMap(_.toDoubleOption).getOrElse(0.25).max(0.0)
  private val PonderReplyMinMs =
    sys.env.get("CHESS_PONDER_REPLY_MIN_MS").flatMap(_.toLongOption).getOrElse(20L).max(1L)
  private val PonderReplyMaxMs =
    sys.env.get("CHESS_PONDER_REPLY_MAX_MS").flatMap(_.toLongOption).filter(_ > 0L)
  private val ttStamp = new AtomicLong(0L)
  private val TTClusterSize = 4
  private val TTClusterCount =
    nextPowerOfTwo(sys.env.get("CHESS_TT_CLUSTERS").flatMap(_.toIntOption).getOrElse(1 << 18).max(1024).min(1 << 21))
  private val TTClusterMask = TTClusterCount - 1
  private val ttSlots = new AtomicReferenceArray[TTEntry](TTClusterCount * TTClusterSize)
  private val ttLocks = Array.fill(4096)(new Object)
  private val statusCache = new ConcurrentHashMap[Long, GameStatus]()
  private val statusEvictionQueue = new ConcurrentLinkedQueue[Long]()
  private val statusCount = new AtomicLong(0L)
  private val MaxStatusEntries = 200_000
  private val moveCache = new ConcurrentHashMap[Long, List[Move]]()
  private val moveEvictionQueue = new ConcurrentLinkedQueue[Long]()
  private val moveCount = new AtomicLong(0L)
  private val MaxMoveEntries = 300_000
  private val evalCache = new ConcurrentHashMap[Long, java.lang.Double]()
  private val evalEvictionQueue = new ConcurrentLinkedQueue[Long]()
  private val evalCount = new AtomicLong(0L)
  private val MaxEvalEntries = 300_000

  private final case class TTEntry(key: Long, score: Double, depth: Int, bound: Int, best: Option[Move], stamp: Long)
  private val killers: Array[Array[Move | Null]] = Array.fill(MaxSearchDepth + 1, 2)(null)
  private val history: Array[Array[Array[Int]]] = Array.fill(2, 64, 64)(0)
  private val counterMoves: Array[Array[Array[Move | Null]]] = Array.fill(2, 64, 64)(null)
  private val continuationHistory: Array[Array[Array[Int]]] = Array.fill(2, 64, 64)(0)
  private val captureHistory: Array[Array[Array[Array[Int]]]] = Array.fill(2, 6, 64, 6)(0)
  private final case class SearchStats(
    betaCutoffs: Long = 0L,
    heuristicCutoffs: Long = 0L,
    heuristicSavedNodesEstimate: Double = 0.0,
    killerCutoffs: Long = 0L,
    counterCutoffs: Long = 0L,
    historyCutoffs: Long = 0L,
    continuationCutoffs: Long = 0L,
    captureHistoryCutoffs: Long = 0L
  )
  private final case class TimeProfile(
    var totalNs: Long = 0L,
    var tablebaseLookupNs: Long = 0L,
    var openingLookupNs: Long = 0L,
    var searchNs: Long = 0L,
    var orderMovesNs: Long = 0L,
    var evalNs: Long = 0L,
    var evalHceNs: Long = 0L,
    var moveGenNs: Long = 0L,
    var applyMoveNs: Long = 0L,
    var statusNs: Long = 0L,
    var ttNs: Long = 0L,
    var heuristicNs: Long = 0L
  )
  private final case class RootSearch(move: Move, scoreCp: Double, nodes: Long, elapsedMs: Long, stats: SearchStats)
  final case class SearchResult(move: Move, scoreCp: Double, depth: Int, nodes: Long)
  private final case class AspirationStats(hit: Boolean, failLow: Int, failHigh: Int, retries: Int)
  private final case class OpeningHit(move: Move, weight: Int)
  private final case class TablebaseHit(move: Move, wdl: Int, dtz: Option[Int], dtm: Option[Int], source: String)
  private final case class OrderingContext(
    activeColor: Color,
    activeColorIndex: Int,
    depthIndex: Int,
    ownKing: Option[Pos],
    ownKingDanger: Int,
    useTacticalOrdering: Boolean,
    useDefensiveOrdering: Boolean
  )

  private val zobristRandom = new SplittableRandom(0x1A2B3C4D5E6F7788L)
  private def nextZobrist(): Long =
    val x = zobristRandom.nextLong()
    if x != 0L then x else 0x9E3779B97F4A7C15L

  private val statusFiftyMoveZobrist: Long = nextZobrist()
  private val statusRepetitionZobrist: Array[Long] = Array.fill(4)(nextZobrist())
  private val evalNamespaceZobrist: Map[String, Long] = Map(
    "hce" -> nextZobrist(),
    "hce_fast" -> nextZobrist(),
    "root_hce" -> nextZobrist()
  )

  private lazy val persistenceOpt =
    Try {
      PersistenceModule.build().unsafeRunSync()
    }.toOption

  private lazy val hceEvaluator: HceEvaluator =
    HceEvaluator.default
  private lazy val fastHceEvaluator: HceEvaluator =
    HceEvaluator.fast

  def bestMove(state: GameState, timeLimitMs: Long): Option[Move] =
    bestMoveWithStats(state, timeLimitMs).map(_.move)

  def bestMoveWithStats(state: GameState, timeLimitMs: Long): Option[SearchResult] =
    runSearch(state, timeLimitMs, SearchMode.Search, logFinalMove = true)

  def ponder(state: GameState, timeLimitMs: Long): Unit =
    ponderLine(state, timeLimitMs)
    ()

  def ponderLine(state: GameState, timeLimitMs: Long): Option[PonderResult] =
    val started = System.currentTimeMillis()
    val totalBudget = math.max(1L, PonderTotalCapMs.map(cap => math.min(timeLimitMs, cap)).getOrElse(timeLimitMs))
    val fractionalPredict = math.round(totalBudget.toDouble * PonderPredictFraction)
    val predictBudget = math.max(PonderPredictMinMs, math.min(PonderPredictMaxMs, fractionalPredict))
    runSearch(state, predictBudget, SearchMode.PonderPredict, logFinalMove = false).map { prediction =>
      val expectedMove = prediction.move
      val expectedState = GameRules.applyMove(state, expectedMove)
      val elapsed = System.currentTimeMillis() - started
      val remaining = math.max(1L, totalBudget - elapsed)
      val replyBudget = PonderReplyMaxMs.map(cap => math.min(remaining, cap)).getOrElse(remaining)
      val replySearch =
        if replyBudget >= PonderReplyMinMs then runSearch(expectedState, replyBudget, SearchMode.PonderReply, logFinalMove = false)
        else None
      val plannedReply = replySearch.map(_.move)
      val depth = math.max(prediction.depth, replySearch.map(_.depth).getOrElse(0))
      val warmedNodes = prediction.nodes + replySearch.map(_.nodes).getOrElse(0L)
      val replyText = plannedReply.map(m => s" reply=${m.toInputString}").getOrElse(" reply=none")
      println(s"[AI][PONDER] depth=$depth warmednodes=$warmedNodes expected=${expectedMove.toInputString}$replyText")
      PonderResult(expectedMove, expectedState.toFen, plannedReply, depth, warmedNodes)
    }

  private def runSearch(state: GameState, timeLimitMs: Long, mode: SearchMode, logFinalMove: Boolean): Option[SearchResult] =
    val searchState =
      if state.history.isEmpty then state
      else state.copy(history = Vector.empty, repetitionCounts = state.repetitionCounts)
    val profile = TimeProfile()
    val startedAll = System.nanoTime()
    val legalMoves = cachedLegalMoves(searchState, positionHash(searchState), profile)
    if legalMoves.isEmpty then return None
    if legalMoves.size == 1 then return legalMoves.headOption.map(move => SearchResult(move, 0.0, 0, 0L))

    if canUseTablebase(searchState) then
      val tbStart = System.nanoTime()
      val tablebaseMove = lookupTablebaseMove(searchState, legalMoves)
      profile.tablebaseLookupNs += (System.nanoTime() - tbStart)
      if mode == SearchMode.Search then
        tablebaseMove.foreach { hit =>
          val verdict =
            if hit.wdl > 0 then "win"
            else if hit.wdl < 0 then "loss"
            else "draw"
          val mateInfo = hit.dtm.map(x => s"mateIn=$x").getOrElse("mateIn=n/a")
          val dtzInfo = hit.dtz.map(x => s"dtz=$x").getOrElse("dtz=n/a")
          println(s"[AI][MOVE] source=${hit.source} move=${hit.move.toInputString} pieces=${searchState.board.pieceCount} verdict=$verdict $mateInfo $dtzInfo")
        }
      if tablebaseMove.nonEmpty then
        if logFinalMove then
          profile.totalNs = System.nanoTime() - startedAll
          logTimeProfile(profile, nodes = 0L)
        return tablebaseMove.map(hit => SearchResult(hit.move, 0.0, 0, 0L))

    val currentPly = plyFromState(searchState)
    val shouldQueryOpeningDb =
      mode == SearchMode.Search &&
      currentPly <= MaxOpeningBookPly &&
      searchState.board.pieceCount > 10
    val bookMove =
      if shouldQueryOpeningDb then
        val obStart = System.nanoTime()
        val bm = lookupOpeningMove(searchState, legalMoves)
        profile.openingLookupNs += (System.nanoTime() - obStart)
        bm
      else None
    val validatedBookMove =
      bookMove.filterNot(hit => isOpeningMoveTacticallyUnsafe(searchState, hit.move, profile))
    if mode == SearchMode.Search then
      bookMove.foreach { hit =>
        if validatedBookMove.isEmpty then
          println(s"[AI][OPENING] rejected move=${hit.move.toInputString} reason=tactical_validation_failed")
        else
          println(s"[AI][MOVE] source=OPENING_DB move=${hit.move.toInputString} weight=${hit.weight}")
      }
    if validatedBookMove.nonEmpty then
      if logFinalMove then
        profile.totalNs = System.nanoTime() - startedAll
        logTimeProfile(profile, nodes = 0L)
      return validatedBookMove.map(hit => SearchResult(hit.move, 0.0, 0, 0L))

    val start = System.currentTimeMillis()
    val deadline = start + math.max(1L, timeLimitMs)

    var best: Option[Move] = None
    var lastComplete: Option[Move] = None
    var lastScoreCp = 0.0
    var completedDepth = 0
    var completedNodes = 0L
    var completedStats = SearchStats()
    var previousDepthElapsedMs = 0L
    var lastDepthElapsedMs = 0L
    var depth = 1

    val searchStart = System.nanoTime()
    boundary:
      while depth <= MaxSearchDepth do
        if System.currentTimeMillis() >= deadline - 10 then boundary.break()
        if !shouldStartDepth(depth, completedDepth, lastDepthElapsedMs, previousDepthElapsedMs, deadline) then
          if mode == SearchMode.Search then
            val remainingMs = (deadline - System.currentTimeMillis()).max(0L)
            val estimateMs = estimateNextDepthMs(lastDepthElapsedMs, previousDepthElapsedMs)
            println(s"[AI][SEARCH] skipDepth=$depth remainingMs=$remainingMs estimatedMs=$estimateMs reason=insufficient_time")
          boundary.break()
        val useAspiration = depth >= 2 && best.nonEmpty
        val base = lastScoreCp
        var window = AspirationInitialWindow
        var alphaWindow = if useAspiration then sanitizeScore(base - window) else Double.NegativeInfinity
        var betaWindow = if useAspiration then sanitizeScore(base + window) else Double.PositiveInfinity
        var rootResult: Option[RootSearch] = None
        var failLow = 0
        var failHigh = 0
        var retries = 0
        var done = false
        val depthStartedAt = System.currentTimeMillis()
        while !done do
          searchRoot(searchState, legalMoves, depth, deadline, profile, alphaWindow, betaWindow) match
            case Some(rs) =>
              completedNodes += rs.nodes
              completedStats = mergeStats(completedStats, rs.stats)
              if useAspiration && rs.scoreCp <= alphaWindow then
                failLow += 1
                retries += 1
                window *= 2.0
                alphaWindow = sanitizeScore(base - window)
                betaWindow = sanitizeScore(base + window)
              else if useAspiration && rs.scoreCp >= betaWindow then
                failHigh += 1
                retries += 1
                window *= 2.0
                alphaWindow = sanitizeScore(base - window)
                betaWindow = sanitizeScore(base + window)
              else
                rootResult = Some(rs)
                done = true
            case None =>
              rootResult = None
              done = true
        val aspStats =
          if useAspiration then AspirationStats(hit = retries == 0, failLow = failLow, failHigh = failHigh, retries = retries)
          else AspirationStats(hit = false, failLow = 0, failHigh = 0, retries = 0)
        rootResult match
          case Some(rs) =>
            val depthElapsedMs = (System.currentTimeMillis() - depthStartedAt).max(1L)
            best = Some(rs.move)
            lastComplete = Some(rs.move)
            lastScoreCp = rs.scoreCp
            completedDepth = depth
            previousDepthElapsedMs = lastDepthElapsedMs
            lastDepthElapsedMs = depthElapsedMs
            if mode == SearchMode.Search then
              val evalPawnsText = f"${rs.scoreCp}%.3f"
              println(
                s"[AI][SEARCH] depth=$depth nodes=${rs.nodes} timeMs=$depthElapsedMs evalPawns=$evalPawnsText pv=${rs.move.toInputString} " +
                s"aspiration=${if useAspiration then (if aspStats.hit then "hit" else "miss") else "off"} failLow=${aspStats.failLow} failHigh=${aspStats.failHigh} retries=${aspStats.retries}"
              )
          case None =>
            boundary.break()
        depth += 1
    profile.searchNs += (System.nanoTime() - searchStart)

    val picked = best.orElse(lastComplete).orElse(legalMoves.headOption)
    if logFinalMove then
      logSearchHeuristicSavings(completedNodes, completedStats)
      picked.foreach { move =>
        val evalPawnsText = f"$lastScoreCp%.3f"
        println(s"[AI][MOVE] source=SEARCH move=${move.toInputString} evalPawns=$evalPawnsText")
      }
      profile.totalNs = System.nanoTime() - startedAll
      logTimeProfile(profile, completedNodes)
    picked.map(move => SearchResult(move, lastScoreCp, completedDepth, completedNodes))

  private def shouldStartDepth(
    depth: Int,
    completedDepth: Int,
    lastDepthElapsedMs: Long,
    previousDepthElapsedMs: Long,
    deadline: Long
  ): Boolean =
    if depth < DepthStartMinDepth || completedDepth <= 0 || lastDepthElapsedMs <= 0L then true
    else
      val remainingMs = deadline - System.currentTimeMillis()
      val estimatedMs = estimateNextDepthMs(lastDepthElapsedMs, previousDepthElapsedMs)
      remainingMs >= math.ceil(estimatedMs.toDouble * DepthStartSafetyMultiplier).toLong + DepthStartReserveMs

  private def estimateNextDepthMs(lastDepthElapsedMs: Long, previousDepthElapsedMs: Long): Long =
    val growth =
      if previousDepthElapsedMs > 0L then
        (lastDepthElapsedMs.toDouble / previousDepthElapsedMs.toDouble)
          .max(DepthStartMinGrowth)
          .min(DepthStartMaxGrowth)
      else DepthStartFallbackGrowth
    math.ceil(lastDepthElapsedMs.toDouble * growth).toLong.max(1L)

  private def searchRoot(
    state: GameState,
    legalMoves: List[Move],
    depth: Int,
    deadline: Long,
    profile: TimeProfile,
    startAlpha: Double,
    startBeta: Double
  ): Option[RootSearch] = boundary:
    val started = System.currentTimeMillis()
    val nodes = Array(0L)
    val stats = Array(SearchStats())
    val rootKey = searchHash(state)
    val ttMoveRoot = Option(getTt(rootKey)).flatMap(_.best)
    val ordered = orderMoves(state, legalMoves, depth, None, ttMoveRoot, profile)
    var alpha = startAlpha
    val beta = startBeta
    var bestMove: Option[Move] = None
    var bestScore = Double.NegativeInfinity
    val repetitionFlags = ordered.map(m => wouldCauseImmediateThreefold(state, m))
    val materialLeadCp = materialLeadCpFor(state, state.activeColor)
    val stalemateFlags =
      if materialLeadCp >= StalemateAvoidMaterialCp then ordered.map(m => wouldCauseImmediateStalemate(state, m))
      else ordered.map(_ => false)
    val immediateMateFlags = ordered.map(m => wouldAllowImmediateMate(state, m))
    val rootDrawScore = drawScoreForRoot(rootStaticScore(state, profile))

    for (move, idx) <- ordered.zipWithIndex do
      if System.currentTimeMillis() >= deadline then boundary.break(None)
      val isRepetitionMove = repetitionFlags(idx)
      val isStalemateMove = stalemateFlags(idx)
      val allowsImmediateMate = immediateMateFlags(idx)
      val score =
        if allowsImmediateMate then
          matedScore(plyFromRoot = 2)
        else if isRepetitionMove || isStalemateMove then
          rootDrawScore
        else
          val amStart = System.nanoTime()
          val child = fastApplyMove(state, move)
          profile.applyMoveNs += (System.nanoTime() - amStart)
          sanitizeScore(-negamax(child, depth - 1, -beta, -alpha, deadline, nodes, stats, Some(move), profile, allowNullMove = true, plyFromRoot = 1))
      if score > bestScore then
        bestScore = score
        bestMove = Some(move)
      if score > alpha then alpha = score

    val elapsed = System.currentTimeMillis() - started
    bestMove.map { move =>
      val rootScore = sanitizeScore(bestScore)
      val bound =
        if rootScore <= startAlpha then 2
        else if rootScore >= startBeta then 1
        else 0
      val ttPutStart = System.nanoTime()
      putTt(
        rootKey,
        scoreToTt(rootScore, plyFromRoot = 0),
        depth,
        bound,
        Some(move)
      )
      profile.ttNs += (System.nanoTime() - ttPutStart)
      RootSearch(move, rootScore, nodes(0), elapsed, stats(0))
    }

  private def negamax(
    state: GameState,
    depth: Int,
    alpha: Double,
    beta: Double,
    deadline: Long,
    nodes: Array[Long],
    stats: Array[SearchStats],
    prevMove: Option[Move],
    profile: TimeProfile,
    allowNullMove: Boolean,
    plyFromRoot: Int
  ): Double = boundary:
    nodes(0) += 1
    if System.currentTimeMillis() >= deadline then boundary.break(sanitizeScore(alpha))

    if repetitionCountForCurrentPosition(state) >= 3 then
      boundary.break(0.0)

    if canUseTablebase(state) then
      val tbStart = System.nanoTime()
      val tbScore = SyzygyProbe.probeWdl(state).map(tablebaseScore)
      profile.tablebaseLookupNs += (System.nanoTime() - tbStart)
      tbScore.foreach(score => boundary.break(score))

    val legalKey = positionHash(state)
    val positionKey = searchHash(state)
    val ttStart = System.nanoTime()
    val ttHit = getTt(positionKey)
    profile.ttNs += (System.nanoTime() - ttStart)
    var a = alpha
    if ttHit != null && ttHit.depth >= depth then
      val ttScore = scoreFromTt(ttHit.score, plyFromRoot)
      ttHit.bound match
        case 0 => boundary.break(ttScore)
        case 1 => a = math.max(a, ttScore)
        case 2 =>
          if ttScore <= alpha then boundary.break(ttScore)
        case _ => ()
      if a >= beta then boundary.break(sanitizeScore(ttScore))

    if depth <= 0 then
      boundary.break(quiescence(state, a, beta, deadline, nodes, profile, plyFromRoot, qDepth = 0))

    val legalMoves = cachedLegalMoves(state, legalKey, profile)

    if legalMoves.isEmpty then
      cachedStatus(state, statusHash(state), profile) match
        case GameStatus.Checkmate(_) =>
          boundary.break(matedScore(plyFromRoot))
        case GameStatus.Stalemate | GameStatus.Draw(_) =>
          boundary.break(0.0)
        case _ =>
          boundary.break(0.0)

    // Avoid expensive status checks on every interior node; only do them when
    // draw conditions can realistically trigger despite legal moves.
    if state.halfMoveClock >= 100 then
      cachedStatus(state, statusHash(state), profile) match
        case GameStatus.Draw(_) =>
          boundary.break(0.0)
        case _ => ()

    val inCheck = MoveGenerator.isInCheck(state, state.activeColor)
    lazy val staticScore = staticEvalCp(state, profile, depth)
    val canUseStaticPruning =
      !inCheck &&
        !isMateScore(a) &&
        !isMateScore(beta) &&
        state.board.pieceCount > SyzygyProbe.maxPieces

    if canUseStaticPruning &&
      depth <= ReverseFutilityMaxDepth &&
      staticScore - reverseFutilityMargin(depth) >= beta
    then
      boundary.break(sanitizeScore(beta))

    if canUseStaticPruning &&
      depth <= RazoringMaxDepth &&
      staticScore + razoringMargin(depth) <= a
    then
      boundary.break(quiescence(state, a, beta, deadline, nodes, profile, plyFromRoot, qDepth = 0))

    // Null-move pruning (safe guards to reduce zugzwang/pathological risk).
    if allowNullMove &&
      depth >= 3 &&
      !inCheck &&
      allowNullMovePruningInPosition(state)
    then
      val nullState = makeNullMoveState(state)
      val r = NullMoveReduction
      val nullScore = sanitizeScore(-negamax(
        nullState,
        depth - 1 - r,
        -beta,
        -beta + 1,
        deadline,
        nodes,
        stats,
        None,
        profile,
        allowNullMove = false,
        plyFromRoot = plyFromRoot + 1
      ))
      if nullScore >= beta then
        boundary.break(beta)

    val originalAlpha = a
    var currentAlpha = a
    val ttBest = Option(ttHit).flatMap(_.best)
    val ordered = orderMoves(state, legalMoves, depth, prevMove, ttBest, profile)
    val nodeStartAtThisPosition = nodes(0)
    var bestSeen: Option[Move] = None
    var moveIndex = 0
    var searchedMoveCount = 0

    for move <- ordered do
      if System.currentTimeMillis() >= deadline then boundary.break(currentAlpha)
      val isCaptureMove = isCapture(state, move)
      val quietMove = !isCaptureMove && move.promotion.isEmpty
      val skipMove =
        quietMove &&
          canUseStaticPruning &&
          shouldPruneQuietMove(state, move, depth, moveIndex, currentAlpha, staticScore, inCheck, prevMove, ttBest)
      if !skipMove then
        val amStart = System.nanoTime()
        val child = fastApplyMove(state, move)
        profile.applyMoveNs += (System.nanoTime() - amStart)
        val useLmr =
          depth >= 3 &&
          moveIndex >= 3 &&
          quietMove &&
          !inCheck
        val fullDepth = depth - 1
        def fullWindowSearch(): Double =
          sanitizeScore(-negamax(child, fullDepth, -beta, -currentAlpha, deadline, nodes, stats, Some(move), profile, allowNullMove = true, plyFromRoot = plyFromRoot + 1))
        def nullWindowSearch(searchDepth: Int): Double =
          sanitizeScore(-negamax(
            child,
            searchDepth,
            -currentAlpha - PvsNullWindow,
            -currentAlpha,
            deadline,
            nodes,
            stats,
            Some(move),
            profile,
            allowNullMove = true,
            plyFromRoot = plyFromRoot + 1
          ))
        val score =
          if searchedMoveCount == 0 then
            fullWindowSearch()
          else
            val probeDepth = if useLmr then (fullDepth - LmrReduction).max(0) else fullDepth
            val probe = nullWindowSearch(probeDepth)
            if probe > currentAlpha && (useLmr || probe < beta) then
              fullWindowSearch()
            else probe
        searchedMoveCount += 1
        if score > currentAlpha then
          currentAlpha = score
          bestSeen = Some(move)
        if currentAlpha >= beta then
          val c = colorIndex(state.activeColor)
          val from = squareIndex(move.from)
          val to = squareIndex(move.to)
          val d = depth.max(0).min(MaxSearchDepth)
          val isKiller =
            !isCaptureMove && ((killers(d)(0) != null && killers(d)(0) == move) || (killers(d)(1) != null && killers(d)(1) == move))
          val isCounter =
            !isCaptureMove && prevMove.exists { pm =>
              val pFrom = squareIndex(pm.from)
              val pTo = squareIndex(pm.to)
              val cm = counterMoves(c)(pFrom)(pTo)
              cm != null && cm == move
            }
          val hasHistory = !isCaptureMove && history(c)(from)(to) > 0
          val hasContinuation =
            !isCaptureMove && prevMove.exists(pm => continuationHistory(c)(squareIndex(pm.to))(to) > 0)
          val hasCaptureHistory = isCaptureMove && captureHistoryScore(state, state.board, move) > 0
          val isHeuristicCutoff = isKiller || isCounter || hasHistory || hasContinuation || hasCaptureHistory
          val searchedMoves = moveIndex + 1
          val skippedMoves = (ordered.length - searchedMoves).max(0)
          val nodesSpentHere = (nodes(0) - nodeStartAtThisPosition).max(1L)
          val avgNodesPerTriedMove = nodesSpentHere.toDouble / searchedMoves.toDouble
          val savedNodesEstimate =
            if isHeuristicCutoff then skippedMoves.toDouble * avgNodesPerTriedMove
            else 0.0
          val old = stats(0)
          stats(0) = old.copy(
            betaCutoffs = old.betaCutoffs + 1,
            heuristicCutoffs = old.heuristicCutoffs + (if isHeuristicCutoff then 1 else 0),
            heuristicSavedNodesEstimate = old.heuristicSavedNodesEstimate + savedNodesEstimate,
            killerCutoffs = old.killerCutoffs + (if isKiller then 1 else 0),
            counterCutoffs = old.counterCutoffs + (if isCounter then 1 else 0),
            historyCutoffs = old.historyCutoffs + (if hasHistory then 1 else 0),
            continuationCutoffs = old.continuationCutoffs + (if hasContinuation then 1 else 0),
            captureHistoryCutoffs = old.captureHistoryCutoffs + (if hasCaptureHistory then 1 else 0)
          )
          val heuStart = System.nanoTime()
          if isCaptureMove then
            updateCaptureHistory(state, move, depth)
          else
            updateKillers(depth, move)
            updateHistory(state.activeColor, move, depth)
            updateCounterMove(state.activeColor, prevMove, move)
            updateContinuationHistory(state.activeColor, prevMove, move, depth)
          profile.heuristicNs += (System.nanoTime() - heuStart)
          boundary.break(currentAlpha)
      moveIndex += 1

    currentAlpha = sanitizeScore(currentAlpha)
    val bound =
      if currentAlpha <= originalAlpha then 2
      else if currentAlpha >= beta then 1
      else 0
    val ttPutStart = System.nanoTime()
    putTt(positionKey, scoreToTt(sanitizeScore(currentAlpha), plyFromRoot), depth, bound, bestSeen)
    profile.ttNs += (System.nanoTime() - ttPutStart)
    currentAlpha

  private def staticEvalCp(
    state: GameState,
    profile: TimeProfile,
    depth: Int
  ): Double =
    hceEvaluate(state, profile, depth) * ValueScale

  private def quiescence(
    state: GameState,
    alpha: Double,
    beta: Double,
    deadline: Long,
    nodes: Array[Long],
    profile: TimeProfile,
    plyFromRoot: Int,
    qDepth: Int
  ): Double = boundary:
    nodes(0) += 1
    if System.currentTimeMillis() >= deadline then boundary.break(sanitizeScore(alpha))

    cachedStatus(state, statusHash(state), profile) match
      case GameStatus.Checkmate(_) => boundary.break(matedScore(plyFromRoot))
      case GameStatus.Stalemate | GameStatus.Draw(_) => boundary.break(0.0)
      case _ => ()

    val inCheck = MoveGenerator.isInCheck(state, state.activeColor)
    var currentAlpha = alpha
    var standPatScore = Double.NegativeInfinity
    if !inCheck then
      val standPat = staticEvalCp(state, profile, -qDepth)
      standPatScore = standPat
      if standPat >= beta then boundary.break(beta)
      if standPat > currentAlpha then currentAlpha = standPat
      if qDepth >= MaxQuiescenceDepth then boundary.break(sanitizeScore(currentAlpha))

    val moves =
      if inCheck then
        val legalMoves = cachedLegalMoves(state, positionHash(state), profile)
        if legalMoves.isEmpty then boundary.break(matedScore(plyFromRoot))
        if qDepth >= MaxQuiescenceDepth then
          boundary.break(staticEvalCp(state, profile, -qDepth))
        legalMoves
      else
        val noisy = noisyMoves(state, profile).filter(move => shouldSearchNoisyMove(state, move, currentAlpha, standPatScore))
        if qDepth < QuiescenceChecksDepth then
          val checks = cachedLegalMoves(state, positionHash(state), profile).filter { move =>
            !isCapture(state, move) && move.promotion.isEmpty && givesCheck(state, move)
          }
          (noisy ++ checks).distinct
        else noisy
    val ordered = orderMoves(state, moves, 0, None, None, profile)

    for move <- ordered do
      if System.currentTimeMillis() >= deadline then boundary.break(currentAlpha)
      val amStart = System.nanoTime()
      val child = fastApplyMove(state, move)
      profile.applyMoveNs += (System.nanoTime() - amStart)
      val score = sanitizeScore(-quiescence(child, -beta, -currentAlpha, deadline, nodes, profile, plyFromRoot + 1, qDepth + 1))
      if score > currentAlpha then currentAlpha = score
      if currentAlpha >= beta then boundary.break(beta)

    sanitizeScore(currentAlpha)

  private def rootStaticScore(
    state: GameState,
    profile: TimeProfile
  ): Double =
    safeHceEvaluate(hceEvaluator, state, profile, cacheNamespace = "root_hce") * ValueScale

  private def drawScoreForRoot(rootScore: Double): Double =
    if rootScore > RootDrawPolicyThreshold then -RootDrawPolicyBias
    else if rootScore < -RootDrawPolicyThreshold then RootDrawPolicyBias
    else 0.0

  private def hceEvaluate(
    state: GameState,
    profile: TimeProfile,
    depth: Int
  ): Double =
    val inCheck = MoveGenerator.isInCheck(state, state.activeColor)
    val pieceCount = state.board.pieceCount
    val useFastHce = depth <= FastHceAtOrBelowDepth && pieceCount > 10 && !inCheck
    val evaluator = if useFastHce then fastHceEvaluator else hceEvaluator
    safeHceEvaluate(evaluator, state, profile, cacheNamespace = if useFastHce then "hce_fast" else "hce")

  private def orderMoves(
    state: GameState,
    legalMoves: List[Move],
    depth: Int,
    prevMove: Option[Move],
    ttMove: Option[Move],
    profile: TimeProfile
  ): List[Move] =
    val started = System.nanoTime()
    if legalMoves.size <= 1 then legalMoves
    else
      val board = state.board
      val activeColor = state.activeColor
      val useTacticalOrdering = depth >= TacticalOrderingMinDepth
      val useDefensiveOrdering = depth >= DefensiveOrderingMinDepth
      val ownKing = state.kingPos(activeColor)
      val context = OrderingContext(
        activeColor = activeColor,
        activeColorIndex = colorIndex(activeColor),
        depthIndex = depth.max(0).min(MaxSearchDepth),
        ownKing = ownKing,
        ownKingDanger = if useDefensiveOrdering then ownKing.map(kingDangerForOrdering(board, _, activeColor)).getOrElse(0) else 0,
        useTacticalOrdering = useTacticalOrdering,
        useDefensiveOrdering = useDefensiveOrdering
      )
      val out = legalMoves.sortBy(m => -moveOrderingScore(state, board, m, depth, prevMove, ttMove, context))
      profile.orderMovesNs += (System.nanoTime() - started)
      out

  private def moveOrderingScore(
    state: GameState,
    board: Board,
    move: Move,
    depth: Int,
    prevMove: Option[Move],
    ttMove: Option[Move],
    context: OrderingContext
  ): Int =
    val ttScore = if ttMove.contains(move) then 200_000 else 0
    val attacker = board.pieceAtOrNull(move.from)
    val isCaptureMove = isCapture(board, state.enPassantTarget, move)
    val victim = capturedPieceForMove(state, board, move, attacker)
    val attackerValue = if attacker == null then 0 else seePieceValueCp(attacker.pieceType)
    val victimValue = if victim == null then 0 else seePieceValueCp(victim.pieceType)
    val seeCp =
      if depth > 0 && (isCaptureMove || move.promotion.nonEmpty) then staticExchangeEval(state, move)
      else 0
    val captureScore =
      if isCaptureMove then
        val seeBucket =
          if seeCp >= 0 then 8_000 + seeCp
          else seeCp * 4
        100_000 + seeBucket + captureHistoryScoreFor(attacker, victim, move.to) + victimValue - (attackerValue / 20)
      else 0
    val checkScore =
      if context.useTacticalOrdering || move.promotion.nonEmpty then
        checkOrderingScore(state, board, move, attacker, isCaptureMove, seeCp)
      else 0
    val promotionScore = promotionOrderingScore(move, isCaptureMove, checkScore > 0)

    val killerScore =
      if !isCaptureMove then
        val k1 = killers(context.depthIndex)(0)
        val k2 = killers(context.depthIndex)(1)
        if k1 != null && move == k1 then 20_000
        else if k2 != null && move == k2 then 15_000
        else 0
      else 0

    val c = context.activeColorIndex
    val from = squareIndex(move.from)
    val to = squareIndex(move.to)
    val historyScore = if isCaptureMove then 0 else history(c)(from)(to).min(12_000)

    val counterScore =
      if !isCaptureMove then
        prevMove match
          case Some(pm) =>
            val pFrom = squareIndex(pm.from)
            val pTo = squareIndex(pm.to)
            val cm = counterMoves(c)(pFrom)(pTo)
            if cm != null && move == cm then 25_000 else 0
          case None => 0
      else 0
    val continuationScore =
      if !isCaptureMove then
        prevMove.map(pm => continuationHistory(c)(squareIndex(pm.to))(to).min(10_000)).getOrElse(0)
      else 0
    val threatScore =
      if context.useTacticalOrdering && !isCaptureMove && move.promotion.isEmpty then
        threatMoveScore(state, board, move, attacker, checkScore > 0)
      else 0

    val defensiveScore =
      if context.useDefensiveOrdering then defensiveModeOrderingScore(state, board, move, attacker, isCaptureMove, checkScore > 0, context)
      else 0

    ttScore + captureScore + promotionScore + checkScore + threatScore +
      killerScore + counterScore + historyScore + continuationScore + defensiveScore

  private def defensiveModeOrderingScore(
    state: GameState,
    board: Board,
    move: Move,
    attacker: Piece | Null,
    isCaptureMove: Boolean,
    isCheckingMove: Boolean,
    context: OrderingContext
  ): Int =
    val ownKingPosOpt = context.ownKing
    if ownKingPosOpt.isEmpty then 0
    else
      val ownKingPos = ownKingPosOpt.get
      val danger = context.ownKingDanger
      if danger < 18 then 0
      else
        val quietQueenMove =
          attacker != null &&
          attacker.pieceType == PieceType.Queen &&
          !isCaptureMove &&
          move.promotion.isEmpty &&
          !isCheckingMove
        val queenWanderPenalty =
          if quietQueenMove && chebyshevDistance(move.to, ownKingPos) > chebyshevDistance(move.from, ownKingPos) then 14_000
          else if quietQueenMove then 9_000
          else 0
        val castleBonus =
          if attacker != null &&
            attacker.pieceType == PieceType.King &&
            math.abs(move.to.col - move.from.col) == 2
          then 11_000
          else 0
        val defendZoneBonus =
          if attacker != null &&
            !isCaptureMove &&
            attacker.pieceType != PieceType.Queen &&
            chebyshevDistance(move.to, ownKingPos) <= 1
          then 3_600
          else if attacker != null &&
            !isCaptureMove &&
            attacker.pieceType == PieceType.Pawn &&
            chebyshevDistance(move.to, ownKingPos) <= 2
          then 2_400
          else 0
        val attackerCaptureBonus =
          if isCaptureMove then
            board.pieceAtOrNull(move.to) match
              case Piece(enemyColor, PieceType.Queen | PieceType.Rook | PieceType.Knight) if enemyColor == context.activeColor.opposite =>
                8_000
              case Piece(enemyColor, PieceType.Bishop) if enemyColor == context.activeColor.opposite && chebyshevDistance(move.to, ownKingPos) <= 2 =>
                4_500
              case _ => 0
          else 0
        castleBonus + defendZoneBonus + attackerCaptureBonus - queenWanderPenalty

  private def kingDangerForOrdering(board: Board, kingPos: Pos, color: Color): Int =
    val files = ((kingPos.col - 1) to (kingPos.col + 1)).filter(f => f >= 0 && f <= 7)
    var score = 0
    files.foreach { file =>
      val ownPawnOnFile = piecesOnFile(board, file, color, PieceType.Pawn)
      val oppPawnOnFile = piecesOnFile(board, file, color.opposite, PieceType.Pawn)
      val heavyPressure = countPiecesOnFile(board, file, color.opposite, PieceType.Rook, PieceType.Queen)
      if !ownPawnOnFile && !oppPawnOnFile then score += 8
      else if !ownPawnOnFile then score += 5
      score += heavyPressure * 5
    }
    score += countNearbyPieces(board, kingPos, color.opposite, PieceType.Queen, maxDistance = 4, closeBonus = 9, farBonus = 4)
    score += countNearbyPieces(board, kingPos, color.opposite, PieceType.Rook, maxDistance = 4, closeBonus = 7, farBonus = 3)
    score += countNearbyPieces(board, kingPos, color.opposite, PieceType.Knight, maxDistance = 3, closeBonus = 8, farBonus = 3)
    score

  private def piecesOnFile(board: Board, file: Int, color: Color, pieceType: PieceType): Boolean =
    (board.bitboardOf(color, pieceType) & fileMask(file)) != 0L

  private def countPiecesOnFile(board: Board, file: Int, color: Color, pieceTypes: PieceType*): Int =
    val mask = fileMask(file)
    var total = 0
    var i = 0
    while i < pieceTypes.length do
      total += java.lang.Long.bitCount(board.bitboardOf(color, pieceTypes(i)) & mask)
      i += 1
    total

  private def countNearbyPieces(
    board: Board,
    target: Pos,
    color: Color,
    pieceType: PieceType,
    maxDistance: Int,
    closeBonus: Int,
    farBonus: Int
  ): Int =
    var bb = board.bitboardOf(color, pieceType)
    var total = 0
    while bb != 0L do
      val idx = java.lang.Long.numberOfTrailingZeros(bb)
      val pos = posFromSquareIndex(idx)
      val distance = chebyshevDistance(pos, target)
      if distance <= 2 then total += closeBonus
      else if distance <= maxDistance then total += farBonus
      bb &= (bb - 1L)
    total

  private def posFromSquareIndex(idx: Int): Pos =
    Pos(idx % 8, idx / 8)

  private def chebyshevDistance(a: Pos, b: Pos): Int =
    math.max(math.abs(a.col - b.col), math.abs(a.row - b.row))

  private def isCapture(state: GameState, move: Move): Boolean =
    state.board.isOccupied(move.to) || state.enPassantTarget.contains(move.to)

  private def isCapture(board: Board, enPassant: Option[Pos], move: Move): Boolean =
    board.pieceAtOrNull(move.to) != null || enPassant.contains(move.to)

  private def noisyMoves(state: GameState, profile: TimeProfile): List[Move] =
    val started = System.nanoTime()
    val out = scala.collection.mutable.ArrayBuffer.empty[Move]
    val legal = scala.collection.mutable.ArrayBuffer.empty[Move]
    val color = state.activeColor
    state.board.foreachPieceOf(color) {
      case (pos, Piece(_, PieceType.Pawn)) =>
        addNoisyPawnMoves(out, state, pos, color)
      case (pos, Piece(_, PieceType.Knight)) =>
        addNoisyKnightMoves(out, state, pos, color)
      case (pos, Piece(_, PieceType.Bishop)) =>
        addNoisySlidingMoves(out, state, pos, color, NoisyBishopDirections)
      case (pos, Piece(_, PieceType.Rook)) =>
        addNoisySlidingMoves(out, state, pos, color, NoisyRookDirections)
      case (pos, Piece(_, PieceType.Queen)) =>
        addNoisySlidingMoves(out, state, pos, color, NoisyQueenDirections)
      case (pos, Piece(_, PieceType.King)) =>
        addNoisyKingMoves(out, state, pos, color)
      case _ =>
        ()
    }
    var i = 0
    while i < out.length do
      val move = out(i)
      if isLegalNoisyMove(state, move) then legal += move
      i += 1
    profile.moveGenNs += (System.nanoTime() - started)
    legal.toList

  private val NoisyBishopDirections: Array[(Int, Int)] =
    Array((1, 1), (1, -1), (-1, 1), (-1, -1))
  private val NoisyRookDirections: Array[(Int, Int)] =
    Array((1, 0), (-1, 0), (0, 1), (0, -1))
  private val NoisyQueenDirections: Array[(Int, Int)] =
    NoisyBishopDirections ++ NoisyRookDirections
  private val NoisyKnightDeltas: Array[(Int, Int)] =
    Array((2, 1), (2, -1), (-2, 1), (-2, -1), (1, 2), (1, -2), (-1, 2), (-1, -2))
  private val NoisyKingDirections: Array[(Int, Int)] =
    Array((1, 1), (1, -1), (-1, 1), (-1, -1), (1, 0), (-1, 0), (0, 1), (0, -1))
  private val BadCaptureSeePruneCp = -90

  private def reverseFutilityMargin(depth: Int): Double =
    0.85 * depth.max(1).toDouble

  private def razoringMargin(depth: Int): Double =
    if depth <= 1 then 1.25 else 2.15

  private def futilityMargin(depth: Int): Double =
    if depth <= 1 then 0.95 else 1.75

  private def lateMovePruneThreshold(depth: Int): Int =
    depth match
      case d if d <= 1 => 7
      case 2 => 10
      case _ => 14

  private def shouldPruneQuietMove(
    state: GameState,
    move: Move,
    depth: Int,
    moveIndex: Int,
    currentAlpha: Double,
    staticScore: Double,
    inCheck: Boolean,
    prevMove: Option[Move],
    ttMove: Option[Move]
  ): Boolean =
    if inCheck || ttMove.contains(move) || hasQuietOrderingSupport(state, move, depth, prevMove) then false
    else
      val attacker = state.board.pieceAtOrNull(move.from)
      val checkingMove =
        attacker != null &&
          isPotentialCheckCandidate(state, state.board, move, attacker) &&
          givesCheck(state, move)
      if checkingMove then false
      else if depth <= SeeQuietPruningMaxDepth && staticExchangeEval(state, move) < -80 then true
      else if depth <= LateMovePruningMaxDepth && moveIndex >= lateMovePruneThreshold(depth) then true
      else depth <= FutilityMaxDepth && staticScore + futilityMargin(depth) <= currentAlpha

  private def hasQuietOrderingSupport(
    state: GameState,
    move: Move,
    depth: Int,
    prevMove: Option[Move]
  ): Boolean =
    val c = colorIndex(state.activeColor)
    val from = squareIndex(move.from)
    val to = squareIndex(move.to)
    val d = depth.max(0).min(MaxSearchDepth)
    val isKiller =
      (killers(d)(0) != null && killers(d)(0) == move) ||
        (killers(d)(1) != null && killers(d)(1) == move)
    val isCounter =
      prevMove.exists { pm =>
        val cm = counterMoves(c)(squareIndex(pm.from))(squareIndex(pm.to))
        cm != null && cm == move
      }
    val historySupport = history(c)(from)(to) >= 384
    val continuationSupport =
      prevMove.exists(pm => continuationHistory(c)(squareIndex(pm.to))(to) >= 384)
    isKiller || isCounter || historySupport || continuationSupport

  private def shouldSearchNoisyMove(state: GameState, move: Move, currentAlpha: Double, standPat: Double): Boolean =
    if move.promotion.nonEmpty then true
    else if !isCapture(state, move) then true
    else
      val see = staticExchangeEval(state, move)
      if see < BadCaptureSeePruneCp then false
      else
        val maxGain = deltaPruningGain(state, move)
        if standPat + maxGain + 1.25 <= currentAlpha then
          val attacker = state.board.pieceAtOrNull(move.from)
          attacker != null &&
            isPotentialCheckCandidate(state, state.board, move, attacker) &&
            givesCheck(state, move)
        else true

  private def deltaPruningGain(state: GameState, move: Move): Double =
    val board = state.board
    val attacker = board.pieceAtOrNull(move.from)
    val victim = capturedPieceForMove(state, board, move, attacker)
    val captured = if victim == null then 0 else seePieceValueCp(victim.pieceType)
    val promotion = (attacker, move.promotion) match
      case (p, Some(pt)) if p != null => seePieceValueCp(pt) - seePieceValueCp(p.pieceType)
      case _ => 0
    (captured + promotion.max(0)).toDouble / 100.0

  private def promotionOrderingScore(move: Move, isCaptureMove: Boolean, isCheck: Boolean): Int =
    move.promotion match
      case Some(PieceType.Queen) => 48_000
      case Some(PieceType.Knight) =>
        if isCheck then 36_000 else if isCaptureMove then 24_000 else 8_000
      case Some(PieceType.Rook) =>
        if isCheck then 18_000 else if isCaptureMove then 16_000 else 5_000
      case Some(PieceType.Bishop) =>
        if isCheck then 16_000 else if isCaptureMove then 14_000 else 4_000
      case Some(PieceType.Pawn | PieceType.King) => 0
      case None => 0

  private def checkOrderingScore(
    state: GameState,
    board: Board,
    move: Move,
    attacker: Piece | Null,
    isCaptureMove: Boolean,
    seeCp: Int
  ): Int =
    if attacker == null || !isPotentialCheckCandidate(state, board, move, attacker) then 0
    else if !givesCheck(state, move) then 0
    else
      val base = if isCaptureMove then 18_000 else 15_000
      val safety = if seeCp < -100 then -10_000 else 0
      val promotion = if move.promotion.nonEmpty then 8_000 else 0
      base + safety + promotion

  private def threatMoveScore(
    state: GameState,
    board: Board,
    move: Move,
    attacker: Piece | Null,
    alreadyGivesCheck: Boolean
  ): Int =
    if attacker == null then 0
    else
      val movedType = move.promotion.getOrElse(attacker.pieceType)
      val materialThreat = bestMaterialThreatScore(board, move, attacker.color, movedType)
      val kingThreat =
        if alreadyGivesCheck then 0
        else
          state.kingPos(attacker.color.opposite).map { king =>
            val distance = chebyshevDistance(move.to, king)
            val piecePressure =
              movedType match
                case PieceType.Queen => 2_800
                case PieceType.Rook => 2_200
                case PieceType.Bishop | PieceType.Knight => 1_800
                case PieceType.Pawn => 900
                case PieceType.King => 0
            if distance <= 2 then piecePressure + (2 - distance).max(0) * 700 else 0
          }.getOrElse(0)
      materialThreat + kingThreat

  private def bestMaterialThreatScore(
    board: Board,
    move: Move,
    color: Color,
    pieceType: PieceType
  ): Int =
    var best = 0
    def consider(targetPos: Pos): Unit =
      if targetPos.isValid && targetPos != move.from then
        val target = board.pieceAtOrNull(targetPos)
        if target != null && target.color == color.opposite && target.pieceType != PieceType.King then
          val victimValue = seePieceValueCp(target.pieceType)
          val attackerValue = seePieceValueCp(pieceType)
          val defended = MoveGenerator.isAttackedBy(board, targetPos, color.opposite)
          val score =
            if !defended then 6_000 + victimValue / 3
            else if attackerValue < victimValue then 3_500 + (victimValue - attackerValue) / 2
            else 0
          if score > best then best = score

    pieceType match
      case PieceType.Pawn =>
        val dir = if color == Color.White then 1 else -1
        consider(Pos(move.to.col - 1, move.to.row + dir))
        consider(Pos(move.to.col + 1, move.to.row + dir))
      case PieceType.Knight =>
        var i = 0
        while i < NoisyKnightDeltas.length do
          val (dc, dr) = NoisyKnightDeltas(i)
          consider(Pos(move.to.col + dc, move.to.row + dr))
          i += 1
      case PieceType.Bishop =>
        scanThreatRays(board, move, color, NoisyBishopDirections, consider)
      case PieceType.Rook =>
        scanThreatRays(board, move, color, NoisyRookDirections, consider)
      case PieceType.Queen =>
        scanThreatRays(board, move, color, NoisyQueenDirections, consider)
      case PieceType.King =>
        var i = 0
        while i < NoisyKingDirections.length do
          val (dc, dr) = NoisyKingDirections(i)
          consider(Pos(move.to.col + dc, move.to.row + dr))
          i += 1
    best

  private def scanThreatRays(
    board: Board,
    move: Move,
    color: Color,
    directions: Array[(Int, Int)],
    consider: Pos => Unit
  ): Unit =
    var dirIndex = 0
    while dirIndex < directions.length do
      val (dc, dr) = directions(dirIndex)
      var to = move.to + (dc, dr)
      var blocked = false
      while to.isValid && !blocked do
        val target = if to == move.from then null else board.pieceAtOrNull(to)
        if target == null then
          to = to + (dc, dr)
        else
          if target.color == color.opposite then consider(to)
          blocked = true
      dirIndex += 1

  private def staticExchangeEval(state: GameState, move: Move): Int =
    val board = state.board
    val movingPiece = board.pieceAtOrNull(move.from)
    if movingPiece == null then 0
    else
      val capturedPiece = capturedPieceForMove(state, board, move, movingPiece)
      val promotionDelta = move.promotion match
        case Some(pt) => seePieceValueCp(pt) - seePieceValueCp(movingPiece.pieceType)
        case None => 0
      val enPassantCapturePos =
        if movingPiece.pieceType == PieceType.Pawn && state.enPassantTarget.contains(move.to) then
          val capturedRow = if movingPiece.color == Color.White then move.to.row - 1 else move.to.row + 1
          Pos(move.to.col, capturedRow)
        else null
      val resultingPiece = move.promotion match
        case Some(pt) => Piece(movingPiece.color, pt)
        case None => movingPiece
      val after =
        board.applyMoveUnchecked(
          from = move.from,
          to = move.to,
          movingPiece = movingPiece,
          resultingPiece = resultingPiece,
          enPassantCapturePos = enPassantCapturePos
        )
      val capturedGain = if capturedPiece == null then 0 else seePieceValueCp(capturedPiece.pieceType)
      capturedGain + promotionDelta - staticExchangeRecapture(after, move.to, movingPiece.color.opposite)

  private def staticExchangeRecapture(board: Board, target: Pos, color: Color): Int =
    leastValuableAttacker(board, target, color) match
      case None => 0
      case Some((from, attacker)) =>
        val captured = board.pieceAtOrNull(target)
        if captured == null then 0
        else
          val resultingPiece =
            if attacker.pieceType == PieceType.Pawn && (target.row == 0 || target.row == 7) then Piece(color, PieceType.Queen)
            else attacker
          val after =
            board.applyMoveUnchecked(
              from = from,
              to = target,
              movingPiece = attacker,
              resultingPiece = resultingPiece
            )
          val gain = seePieceValueCp(captured.pieceType) - staticExchangeRecapture(after, target, color.opposite)
          if gain > 0 then gain else 0

  private def capturedPieceForMove(state: GameState, board: Board, move: Move, movingPiece: Piece | Null): Piece | Null =
    if movingPiece != null && movingPiece.pieceType == PieceType.Pawn && state.enPassantTarget.contains(move.to) then
      Piece(movingPiece.color.opposite, PieceType.Pawn)
    else board.pieceAtOrNull(move.to)

  private def seePieceValueCp(pieceType: PieceType): Int =
    pieceType match
      case PieceType.Pawn => 100
      case PieceType.Knight => 320
      case PieceType.Bishop => 330
      case PieceType.Rook => 500
      case PieceType.Queen => 900
      case PieceType.King => 20_000

  private def leastValuableAttacker(board: Board, target: Pos, color: Color): Option[(Pos, Piece)] =
    findPawnAttacker(board, target, color)
      .orElse(findJumpAttacker(board, target, color, PieceType.Knight, NoisyKnightDeltas))
      .orElse(findSlidingAttacker(board, target, color, PieceType.Bishop, NoisyBishopDirections))
      .orElse(findSlidingAttacker(board, target, color, PieceType.Rook, NoisyRookDirections))
      .orElse(findSlidingAttacker(board, target, color, PieceType.Queen, NoisyQueenDirections))

  private def findPawnAttacker(board: Board, target: Pos, color: Color): Option[(Pos, Piece)] =
    val sourceRow = if color == Color.White then target.row - 1 else target.row + 1
    val piece = Piece(color, PieceType.Pawn)
    val left = Pos(target.col - 1, sourceRow)
    if left.isValid && board.pieceAtOrNull(left) == piece then Some(left -> piece)
    else
      val right = Pos(target.col + 1, sourceRow)
      if right.isValid && board.pieceAtOrNull(right) == piece then Some(right -> piece)
      else None

  private def findJumpAttacker(
    board: Board,
    target: Pos,
    color: Color,
    pieceType: PieceType,
    deltas: Array[(Int, Int)]
  ): Option[(Pos, Piece)] =
    val piece = Piece(color, pieceType)
    var found: Option[(Pos, Piece)] = None
    var i = 0
    while i < deltas.length && found.isEmpty do
      val (dc, dr) = deltas(i)
      val from = Pos(target.col + dc, target.row + dr)
      if from.isValid && board.pieceAtOrNull(from) == piece then found = Some(from -> piece)
      i += 1
    found

  private def findSlidingAttacker(
    board: Board,
    target: Pos,
    color: Color,
    pieceType: PieceType,
    directions: Array[(Int, Int)]
  ): Option[(Pos, Piece)] =
    val piece = Piece(color, pieceType)
    var found: Option[(Pos, Piece)] = None
    var dirIndex = 0
    while dirIndex < directions.length && found.isEmpty do
      val (dc, dr) = directions(dirIndex)
      var from = target + (dc, dr)
      var blocked = false
      while from.isValid && !blocked && found.isEmpty do
        val current = board.pieceAtOrNull(from)
        if current == null then from = from + (dc, dr)
        else
          if current == piece then found = Some(from -> piece)
          blocked = true
      dirIndex += 1
    found

  private def captureHistoryScore(state: GameState, board: Board, move: Move): Int =
    val attacker = board.pieceAtOrNull(move.from)
    val victim = capturedPieceForMove(state, board, move, attacker)
    captureHistoryScoreFor(attacker, victim, move.to)

  private def captureHistoryScoreFor(attacker: Piece | Null, victim: Piece | Null, to: Pos): Int =
    if attacker == null || victim == null then 0
    else
      val c = colorIndex(attacker.color)
      captureHistory(c)(pieceTypeIndex(attacker.pieceType))(squareIndex(to))(pieceTypeIndex(victim.pieceType)).min(16_000)

  private def isPotentialCheckCandidate(state: GameState, board: Board, move: Move, attacker: Piece): Boolean =
    state.kingPos(attacker.color.opposite).exists { king =>
      val movedType = move.promotion.getOrElse(attacker.pieceType)
      pieceAttacksSquareAfterMove(board, move.from, move.to, movedType, attacker.color, king) ||
        sharesLine(move.from, king)
    }

  private def pieceAttacksSquareAfterMove(
    board: Board,
    from: Pos,
    piecePos: Pos,
    pieceType: PieceType,
    color: Color,
    target: Pos
  ): Boolean =
    pieceType match
      case PieceType.Pawn =>
        val dir = if color == Color.White then 1 else -1
        target.row - piecePos.row == dir && math.abs(target.col - piecePos.col) == 1
      case PieceType.Knight =>
        val dc = math.abs(target.col - piecePos.col)
        val dr = math.abs(target.row - piecePos.row)
        (dc == 1 && dr == 2) || (dc == 2 && dr == 1)
      case PieceType.Bishop =>
        math.abs(target.col - piecePos.col) == math.abs(target.row - piecePos.row) &&
          clearLineIgnoringFrom(board, from, piecePos, target)
      case PieceType.Rook =>
        (target.col == piecePos.col || target.row == piecePos.row) &&
          clearLineIgnoringFrom(board, from, piecePos, target)
      case PieceType.Queen =>
        sharesLine(piecePos, target) && clearLineIgnoringFrom(board, from, piecePos, target)
      case PieceType.King =>
        chebyshevDistance(piecePos, target) <= 1

  private def clearLineIgnoringFrom(board: Board, from: Pos, a: Pos, b: Pos): Boolean =
    val dc = Integer.signum(b.col - a.col)
    val dr = Integer.signum(b.row - a.row)
    var pos = a + (dc, dr)
    while pos.isValid && pos != b do
      if pos != from && board.isOccupied(pos) then return false
      pos = pos + (dc, dr)
    true

  private def sharesLine(a: Pos, b: Pos): Boolean =
    a.row == b.row || a.col == b.col || math.abs(a.row - b.row) == math.abs(a.col - b.col)

  private def addNoisyPawnMoves(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    pos: Pos,
    color: Color
  ): Unit =
    val board = state.board
    val dir = if color == Color.White then 1 else -1
    val promotionRow = if color == Color.White then 7 else 0
    val oneStep = pos + (0, dir)
    if oneStep.isValid && oneStep.row == promotionRow && board.isEmpty(oneStep) then
      addPromotions(out, pos, oneStep)

    val left = pos + (-1, dir)
    if left.isValid then
      if board.isOccupiedBy(left, color.opposite) then
        if left.row == promotionRow then addPromotions(out, pos, left)
        else out += Move(pos, left)
      else if state.enPassantTarget.contains(left) then out += Move(pos, left)

    val right = pos + (1, dir)
    if right.isValid then
      if board.isOccupiedBy(right, color.opposite) then
        if right.row == promotionRow then addPromotions(out, pos, right)
        else out += Move(pos, right)
      else if state.enPassantTarget.contains(right) then out += Move(pos, right)

  private def addPromotions(out: scala.collection.mutable.ArrayBuffer[Move], from: Pos, to: Pos): Unit =
    out += Move(from, to, Some(PieceType.Queen))
    out += Move(from, to, Some(PieceType.Rook))
    out += Move(from, to, Some(PieceType.Bishop))
    out += Move(from, to, Some(PieceType.Knight))

  private def addNoisyKnightMoves(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    pos: Pos,
    color: Color
  ): Unit =
    val board = state.board
    var i = 0
    while i < NoisyKnightDeltas.length do
      val (dc, dr) = NoisyKnightDeltas(i)
      val to = pos + (dc, dr)
      if to.isValid && board.isOccupiedBy(to, color.opposite) then out += Move(pos, to)
      i += 1

  private def addNoisySlidingMoves(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    pos: Pos,
    color: Color,
    directions: Array[(Int, Int)]
  ): Unit =
    val board = state.board
    var dirIndex = 0
    while dirIndex < directions.length do
      val (dc, dr) = directions(dirIndex)
      var to = pos + (dc, dr)
      var blocked = false
      while to.isValid && !blocked do
        val target = board.pieceAtOrNull(to)
        if target == null then
            to = to + (dc, dr)
        else if target.color == color.opposite then
            out += Move(pos, to)
            blocked = true
        else
            blocked = true
      dirIndex += 1

  private def addNoisyKingMoves(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    pos: Pos,
    color: Color
  ): Unit =
    val board = state.board
    var i = 0
    while i < NoisyKingDirections.length do
      val (dc, dr) = NoisyKingDirections(i)
      val to = pos + (dc, dr)
      if to.isValid && board.isOccupiedBy(to, color.opposite) then out += Move(pos, to)
      i += 1

  private def isLegalNoisyMove(state: GameState, move: Move): Boolean =
    val target = state.board.pieceAtOrNull(move.to)
    (target == null || target.pieceType != PieceType.King) &&
      !MoveGenerator.isInCheck(fastApplyMove(state, move), state.activeColor)

  private def givesCheck(state: GameState, move: Move): Boolean =
    val child = fastApplyMove(state, move)
    MoveGenerator.isInCheck(child, child.activeColor)

  private def fileMask(file: Int): Long =
    0x0101010101010101L << file

  private def updateKillers(depth: Int, move: Move): Unit =
    val d = depth.max(0).min(MaxSearchDepth)
    val k1 = killers(d)(0)
    if k1 == null || k1 != move then
      killers(d)(1) = k1
      killers(d)(0) = move

  private def updateHistory(color: Color, move: Move, depth: Int): Unit =
    val c = colorIndex(color)
    val from = squareIndex(move.from)
    val to = squareIndex(move.to)
    val bonus = (depth.max(1) * depth.max(1)).min(256)
    history(c)(from)(to) += bonus
    if history(c)(from)(to) > 1_000_000 then
      var i = 0
      while i < 64 do
        var j = 0
        while j < 64 do
          history(c)(i)(j) = history(c)(i)(j) / 2
          j += 1
        i += 1

  private def updateContinuationHistory(color: Color, prevMove: Option[Move], reply: Move, depth: Int): Unit =
    prevMove.foreach { pm =>
      val c = colorIndex(color)
      val prevTo = squareIndex(pm.to)
      val to = squareIndex(reply.to)
      val bonus = (depth.max(1) * depth.max(1)).min(256)
      continuationHistory(c)(prevTo)(to) += bonus
      if continuationHistory(c)(prevTo)(to) > 1_000_000 then
        var i = 0
        while i < 64 do
          continuationHistory(c)(prevTo)(i) = continuationHistory(c)(prevTo)(i) / 2
          i += 1
    }

  private def updateCaptureHistory(state: GameState, move: Move, depth: Int): Unit =
    val board = state.board
    val attacker = board.pieceAtOrNull(move.from)
    val victim = capturedPieceForMove(state, board, move, attacker)
    if attacker != null && victim != null then
      val c = colorIndex(attacker.color)
      val mover = pieceTypeIndex(attacker.pieceType)
      val to = squareIndex(move.to)
      val captured = pieceTypeIndex(victim.pieceType)
      val bonus = (depth.max(1) * depth.max(1) * 2).min(512)
      captureHistory(c)(mover)(to)(captured) += bonus
      if captureHistory(c)(mover)(to)(captured) > 1_000_000 then
        var sq = 0
        while sq < 64 do
          var pt = 0
          while pt < 6 do
            captureHistory(c)(mover)(sq)(pt) = captureHistory(c)(mover)(sq)(pt) / 2
            pt += 1
          sq += 1

  private def updateCounterMove(color: Color, prevMove: Option[Move], reply: Move): Unit =
    prevMove.foreach { pm =>
      val c = colorIndex(color)
      val pFrom = squareIndex(pm.from)
      val pTo = squareIndex(pm.to)
      counterMoves(c)(pFrom)(pTo) = reply
    }

  private def makeNullMoveState(state: GameState): GameState =
    val nextHalf = state.halfMoveClock + 1
    val nextHash = ZobristHash.advanceNullMove(state.positionHash, state.enPassantTarget)
    val nextRepetitionCounts = GameState.advanceRepetitionCounts(state.repetitionCounts, nextHash, irreversible = false)
    state match
      case WhiteToMove(b, cr, _, _, fm, _, _, _, _, _, _) =>
        GameState.black(b, cr, None, nextHalf, fm, Vector.empty, positionHash = nextHash, repetitionCounts = nextRepetitionCounts)
      case BlackToMove(b, cr, _, _, fm, _, _, _, _, _, _) =>
        GameState.white(b, cr, None, nextHalf, fm + 1, Vector.empty, positionHash = nextHash, repetitionCounts = nextRepetitionCounts)

  private def hasNonPawnMaterial(state: GameState, color: Color): Boolean =
    val board = state.board
    (board.bitboardOf(color, PieceType.Knight) |
      board.bitboardOf(color, PieceType.Bishop) |
      board.bitboardOf(color, PieceType.Rook) |
      board.bitboardOf(color, PieceType.Queen)) != 0L

  private def allowNullMovePruningInPosition(state: GameState): Boolean =
    val pieces = state.board.pieceCount
    if pieces <= 6 || !hasNonPawnMaterial(state, state.activeColor) then false
    else if pieces <= 10 && isZugzwangProneEndgame(state) then false
    else true

  private def isZugzwangProneEndgame(state: GameState): Boolean =
    val board = state.board
    val queens =
      java.lang.Long.bitCount(board.bitboardOf(Color.White, PieceType.Queen) | board.bitboardOf(Color.Black, PieceType.Queen))
    val rooks =
      java.lang.Long.bitCount(board.bitboardOf(Color.White, PieceType.Rook) | board.bitboardOf(Color.Black, PieceType.Rook))
    val minors =
      java.lang.Long.bitCount(
        board.bitboardOf(Color.White, PieceType.Bishop) |
          board.bitboardOf(Color.Black, PieceType.Bishop) |
          board.bitboardOf(Color.White, PieceType.Knight) |
          board.bitboardOf(Color.Black, PieceType.Knight)
      )
    queens == 0 && rooks == 0 && minors <= 2

  // Search-internal move application without growing history list on every node.
  // This keeps functional game rules equivalent for search while reducing allocation pressure.
  private def fastApplyMove(state: GameState, move: Move): GameState =
    val board = state.board
    val piece = board.get(move.from).get
    val color = piece.color

    val enPassantCapturePos: Pos | Null =
      if piece.pieceType == PieceType.Pawn && state.enPassantTarget.contains(move.to) then
        val capturedRow = if color == Color.White then move.to.row - 1 else move.to.row + 1
        Pos(move.to.col, capturedRow)
      else null

    val (rookFrom, rookTo): (Pos | Null, Pos | Null) =
      if piece.pieceType == PieceType.King then
        val dc = move.to.col - move.from.col
        if math.abs(dc) == 2 then
          val row = move.from.row
          val rookCol = if dc > 0 then 7 else 0
          val newCol = if dc > 0 then 5 else 3
          (Pos(rookCol, row), Pos(newCol, row))
        else (null, null)
      else (null, null)

    val movedPiece = move.promotion match
      case Some(pt) => Piece(color, pt)
      case None => piece

    val boardAfterMove =
      board.applyMoveUnchecked(
        from = move.from,
        to = move.to,
        movingPiece = piece,
        resultingPiece = movedPiece,
        enPassantCapturePos = enPassantCapturePos,
        rookFrom = rookFrom,
        rookTo = rookTo
      )
    val newRights = updateCastlingRightsFast(state.castlingRights, piece, move)
    val newEP =
      if piece.pieceType == PieceType.Pawn && math.abs(move.to.row - move.from.row) == 2 then
        Some(Pos(move.from.col, (move.from.row + move.to.row) / 2))
      else None
    val isCapture = board.isOccupied(move.to) || state.enPassantTarget.contains(move.to)
    val isPawnMove = piece.pieceType == PieceType.Pawn
    val newHalfClock = if isCapture || isPawnMove then 0 else state.halfMoveClock + 1
    val newFullMove = if color == Color.Black then state.fullMoveNumber + 1 else state.fullMoveNumber
    val capturedPiece =
      if enPassantCapturePos != null then Piece(color.opposite, PieceType.Pawn)
      else board.pieceAtOrNull(move.to)
    val capturedPos =
      if enPassantCapturePos != null then enPassantCapturePos else if capturedPiece != null then move.to else null
    val newPositionHash =
      ZobristHash.advance(
        currentHash = state.positionHash,
        activeColor = color,
        castlingRights = state.castlingRights,
        enPassantTarget = state.enPassantTarget,
        move = move,
        movingPiece = piece,
        resultingPiece = movedPiece,
        capturedPiece = capturedPiece,
        capturedPos = capturedPos,
        newCastlingRights = newRights,
        newEnPassantTarget = newEP,
        rookFrom = rookFrom,
        rookTo = rookTo
      )
    val newRepetitionCounts =
      GameState.advanceRepetitionCounts(state.repetitionCounts, newPositionHash, irreversible = isCapture || isPawnMove)

    if color.opposite == Color.White then
      GameState.white(
        boardAfterMove,
        newRights,
        newEP,
        newHalfClock,
        newFullMove,
        Vector.empty,
        positionHash = newPositionHash,
        repetitionCounts = newRepetitionCounts
      )
    else
      GameState.black(
        boardAfterMove,
        newRights,
        newEP,
        newHalfClock,
        newFullMove,
        Vector.empty,
        positionHash = newPositionHash,
        repetitionCounts = newRepetitionCounts
      )

  private def updateCastlingRightsFast(cr: CastlingRights, piece: Piece, move: Move): CastlingRights =
    var updated = cr
    if piece.pieceType == PieceType.King then
      updated = if piece.color == Color.White then updated.disableWhite else updated.disableBlack
    if piece.pieceType == PieceType.Rook then
      move.from match
        case Pos(0, 0) => updated = updated.disableWhiteQueenSide
        case Pos(7, 0) => updated = updated.disableWhiteKingSide
        case Pos(0, 7) => updated = updated.disableBlackQueenSide
        case Pos(7, 7) => updated = updated.disableBlackKingSide
        case _ => ()
    move.to match
      case Pos(0, 0) => updated = updated.disableWhiteQueenSide
      case Pos(7, 0) => updated = updated.disableWhiteKingSide
      case Pos(0, 7) => updated = updated.disableBlackQueenSide
      case Pos(7, 7) => updated = updated.disableBlackKingSide
      case _ => ()
    updated

  private def clearOrderingHeuristics(): Unit =
    var d = 0
    while d <= MaxSearchDepth do
      killers(d)(0) = null
      killers(d)(1) = null
      d += 1

    var c = 0
    while c < 2 do
      var i = 0
      while i < 64 do
        var j = 0
        while j < 64 do
          history(c)(i)(j) = 0
          counterMoves(c)(i)(j) = null
          continuationHistory(c)(i)(j) = 0
          j += 1
        i += 1
      c += 1

    c = 0
    while c < 2 do
      var pt = 0
      while pt < 6 do
        var sq = 0
        while sq < 64 do
          var captured = 0
          while captured < 6 do
            captureHistory(c)(pt)(sq)(captured) = 0
            captured += 1
          sq += 1
        pt += 1
      c += 1

  private def getTt(key: Long): TTEntry | Null =
    val base = ttBaseIndex(key)
    var i = 0
    while i < TTClusterSize do
      val entry = ttSlots.get(base + i)
      if entry != null && entry.key == key then return entry
      i += 1
    null

  private def putTt(key: Long, score: Double, depth: Int, bound: Int, best: Option[Move]): Unit =
    val entry = TTEntry(key, score, depth, bound, best, ttStamp.incrementAndGet())
    val cluster = ttClusterIndex(key)
    val base = cluster * TTClusterSize
    val lock = ttLocks(cluster & (ttLocks.length - 1))
    lock.synchronized {
      var exactIdx = -1
      var emptyIdx = -1
      var victimIdx = base
      var victimQuality = Int.MaxValue
      var victimStamp = Long.MaxValue
      var i = 0
      while i < TTClusterSize do
        val idx = base + i
        val current = ttSlots.get(idx)
        if current == null then
          if emptyIdx < 0 then emptyIdx = idx
        else if current.key == key then
          exactIdx = idx
        else
          val quality = ttReplacementQuality(current)
          if quality < victimQuality || (quality == victimQuality && current.stamp < victimStamp) then
            victimIdx = idx
            victimQuality = quality
            victimStamp = current.stamp
        i += 1

      if exactIdx >= 0 then
        val current = ttSlots.get(exactIdx)
        val mergedBest = entry.best.orElse(Option(current).flatMap(_.best))
        if current == null ||
          entry.depth >= current.depth ||
          entry.bound == 0 ||
          current.bound != 0
        then
          ttSlots.set(exactIdx, entry.copy(best = mergedBest))
        else if current.best.isEmpty && entry.best.nonEmpty then
          ttSlots.set(exactIdx, current.copy(best = entry.best, stamp = entry.stamp))
      else
        val idx = if emptyIdx >= 0 then emptyIdx else victimIdx
        ttSlots.set(idx, entry)
    }

  private def ttReplacementQuality(entry: TTEntry): Int =
    (entry.depth.max(0) << 3) +
      (if entry.bound == 0 then 4 else 0) +
      (if entry.best.nonEmpty then 2 else 0)

  private def ttClusterIndex(key: Long): Int =
    val mixed = key ^ (key >>> 33) ^ (key << 11)
    (mixed.toInt ^ (mixed >>> 32).toInt) & TTClusterMask

  private def ttBaseIndex(key: Long): Int =
    ttClusterIndex(key) * TTClusterSize

  private def nextPowerOfTwo(value: Int): Int =
    var n = 1
    val target = value.max(1)
    while n < target && n < (1 << 30) do n <<= 1
    n

  private def cachedStatus(state: GameState, key: Long, profile: TimeProfile): GameStatus =
    val t0 = System.nanoTime()
    val cached = statusCache.get(key)
    profile.statusNs += (System.nanoTime() - t0)
    if cached != null then cached
    else
      val t1 = System.nanoTime()
      val computed = GameRules.computeStatus(state)
      profile.statusNs += (System.nanoTime() - t1)
      val existing = statusCache.putIfAbsent(key, computed)
      if existing != null then existing
      else
        statusCount.incrementAndGet()
        statusEvictionQueue.add(key)
        evictIfNeeded(statusCache, statusEvictionQueue, statusCount, MaxStatusEntries)
        computed

  private def cachedLegalMoves(state: GameState, key: Long, profile: TimeProfile): List[Move] =
    val t0 = System.nanoTime()
    val cached = moveCache.get(key)
    profile.moveGenNs += (System.nanoTime() - t0)
    if cached != null then cached
    else
      val t1 = System.nanoTime()
      val computed = MoveGenerator.legalMoves(state)
      profile.moveGenNs += (System.nanoTime() - t1)
      val existing = moveCache.putIfAbsent(key, computed)
      if existing != null then existing
      else
        moveCount.incrementAndGet()
        moveEvictionQueue.add(key)
        evictIfNeeded(moveCache, moveEvictionQueue, moveCount, MaxMoveEntries)
        computed

  private def sanitizeScore(score: Double): Double =
    if score.isNaN then 0.0
    else if score.isPosInfinity then ScoreCap
    else if score.isNegInfinity then -ScoreCap
    else if score > ScoreCap then ScoreCap
    else if score < -ScoreCap then -ScoreCap
    else score

  private def matedScore(plyFromRoot: Int): Double =
    -(MATE_SCORE.toDouble - plyFromRoot.toDouble)

  private def tablebaseScore(hit: SyzygyProbe.WdlResult): Double =
    val distance = hit.dtm.orElse(hit.dtz).map(v => math.abs(v)).getOrElse(0)
    math.signum(hit.wdl) match
      case 1 => sanitizeScore(100_000.0 - distance.toDouble)
      case -1 => sanitizeScore(-100_000.0 + distance.toDouble)
      case _ => 0.0

  private def canUseTablebase(state: GameState): Boolean =
    state.board.pieceCount <= SyzygyProbe.maxPieces

  private def isMateScore(score: Double): Boolean =
    math.abs(score) >= MateScoreThreshold

  private def scoreToTt(score: Double, plyFromRoot: Int): Double =
    if !isMateScore(score) then score
    else if score > 0 then sanitizeScore(score + plyFromRoot.toDouble)
    else sanitizeScore(score - plyFromRoot.toDouble)

  private def scoreFromTt(score: Double, plyFromRoot: Int): Double =
    if !isMateScore(score) then score
    else if score > 0 then sanitizeScore(score - plyFromRoot.toDouble)
    else sanitizeScore(score + plyFromRoot.toDouble)

  private def wouldCauseImmediateThreefold(
    state: GameState,
    move: Move
  ): Boolean =
    val child = fastApplyMove(state, move)
    child.repetitionCounts.getOrElse(child.positionHash, 0) >= 3

  private def wouldCauseImmediateStalemate(state: GameState, move: Move): Boolean =
    val child = fastApplyMove(state, move)
    GameRules.computeStatus(child) match
      case GameStatus.Stalemate => true
      case _ => false

  private def wouldAllowImmediateMate(state: GameState, move: Move): Boolean =
    val child = fastApplyMove(state, move)
    val replies = MoveGenerator.legalMoves(child)
    replies.exists { reply =>
      val replyState = fastApplyMove(child, reply)
      MoveGenerator.isInCheck(replyState, replyState.activeColor) &&
        MoveGenerator.legalMoves(replyState).isEmpty
    }

  private def isOpeningMoveTacticallyUnsafe(state: GameState, move: Move, profile: TimeProfile): Boolean =
    if wouldAllowImmediateMate(state, move) then true
    else
      val before = rootStaticScore(state, profile)
      val child = fastApplyMove(state, move)
      val afterForMover = -rootStaticScore(child, profile)
      val tacticalGain = opponentBestTacticalGainCp(child)
      tacticalGain >= OpeningValidationMinGainCp &&
        afterForMover <= before + OpeningValidationEvalSlack

  private def opponentBestTacticalGainCp(state: GameState): Int =
    val legalMoves = MoveGenerator.legalMoves(state)
    var best = 0
    legalMoves.foreach { reply =>
      if isCapture(state, reply) || reply.promotion.nonEmpty then
        val gain = staticExchangeEval(state, reply)
        if gain > best then best = gain
    }
    best

  private def materialLeadCpFor(state: GameState, color: Color): Int =
    materialCpFor(state.board, color) - materialCpFor(state.board, color.opposite)

  private def materialCpFor(board: Board, color: Color): Int =
    java.lang.Long.bitCount(board.bitboardOf(color, PieceType.Pawn)) * PieceType.pieceValue(PieceType.Pawn) * 100 +
      java.lang.Long.bitCount(board.bitboardOf(color, PieceType.Knight)) * PieceType.pieceValue(PieceType.Knight) * 100 +
      java.lang.Long.bitCount(board.bitboardOf(color, PieceType.Bishop)) * PieceType.pieceValue(PieceType.Bishop) * 100 +
      java.lang.Long.bitCount(board.bitboardOf(color, PieceType.Rook)) * PieceType.pieceValue(PieceType.Rook) * 100 +
      java.lang.Long.bitCount(board.bitboardOf(color, PieceType.Queen)) * PieceType.pieceValue(PieceType.Queen) * 100

  private def plyFromState(state: GameState): Int =
    // fullMoveNumber starts at 1; White to move at ply 0, Black at ply 1.
    val base = (state.fullMoveNumber - 1) * 2
    if state.activeColor == Color.White then base else base + 1

  private def positionHash(state: GameState): Long =
    state.positionHash

  private def statusHash(state: GameState): Long =
    val repetitionBucket = state.repetitionCounts.getOrElse(state.positionHash, 0).min(3)
    var h = positionHash(state)
    if state.halfMoveClock >= 100 then h ^= statusFiftyMoveZobrist
    h ^ statusRepetitionZobrist(repetitionBucket)

  private def searchHash(state: GameState): Long =
    statusHash(state)

  private def evalHash(state: GameState, cacheNamespace: String): Long =
    positionHash(state) ^ evalNamespaceZobrist.getOrElse(cacheNamespace, 0L)

  private def repetitionCountForCurrentPosition(state: GameState): Int =
    state.repetitionCounts.getOrElse(state.positionHash, 0)

  private def squareIndex(pos: Pos): Int = pos.row * 8 + pos.col
  private def colorIndex(color: Color): Int = if color == Color.White then 0 else 1
  private def pieceTypeIndex(pieceType: PieceType): Int =
    pieceType match
      case PieceType.Pawn => 0
      case PieceType.Knight => 1
      case PieceType.Bishop => 2
      case PieceType.Rook => 3
      case PieceType.Queen => 4
      case PieceType.King => 5

  private def safeHceEvaluate(
    evaluator: HceEvaluator,
    state: GameState,
    profile: TimeProfile,
    cacheNamespace: String
  ): Double =
    val started = System.nanoTime()
    val cacheKey = evalHash(state, cacheNamespace)
    val cached = evalCache.get(cacheKey)
    val out =
      if cached != null then cached.doubleValue()
      else
        val computed = evaluator.evaluate(state)
        val existing = evalCache.putIfAbsent(cacheKey, java.lang.Double.valueOf(computed))
        if existing != null then existing.doubleValue()
        else
          evalCount.incrementAndGet()
          evalEvictionQueue.add(cacheKey)
          evictIfNeeded(evalCache, evalEvictionQueue, evalCount, MaxEvalEntries)
          computed
    val elapsed = System.nanoTime() - started
    profile.evalNs += elapsed
    profile.evalHceNs += elapsed
    out

  private def mergeStats(a: SearchStats, b: SearchStats): SearchStats =
    SearchStats(
      betaCutoffs = a.betaCutoffs + b.betaCutoffs,
      heuristicCutoffs = a.heuristicCutoffs + b.heuristicCutoffs,
      heuristicSavedNodesEstimate = a.heuristicSavedNodesEstimate + b.heuristicSavedNodesEstimate,
      killerCutoffs = a.killerCutoffs + b.killerCutoffs,
      counterCutoffs = a.counterCutoffs + b.counterCutoffs,
      historyCutoffs = a.historyCutoffs + b.historyCutoffs,
      continuationCutoffs = a.continuationCutoffs + b.continuationCutoffs,
      captureHistoryCutoffs = a.captureHistoryCutoffs + b.captureHistoryCutoffs
    )

  private def logSearchHeuristicSavings(nodes: Long, stats: SearchStats): Unit =
    val heuristicShareOfCutoffs =
      if stats.betaCutoffs > 0 then (stats.heuristicCutoffs.toDouble * 100.0) / stats.betaCutoffs.toDouble
      else 0.0
    val heuristicShareText = f"$heuristicShareOfCutoffs%.2f"
    val savedNodesEstimate = math.round(stats.heuristicSavedNodesEstimate)
    val savedNodesPct =
      if nodes > 0 then (stats.heuristicSavedNodesEstimate * 100.0) / nodes.toDouble
      else 0.0
    val savedNodesPctText = f"$savedNodesPct%.2f"
    println(
      s"[AI][SEARCH-HEURISTICS] nodes=$nodes betaCutoffs=${stats.betaCutoffs} heuristicCuts=${stats.heuristicCutoffs} " +
      s"heuristicShareOfCutoffs=$heuristicShareText savedNodesEstimate=$savedNodesEstimate savedNodesPct=$savedNodesPctText " +
      s"killerCuts=${stats.killerCutoffs} counterCuts=${stats.counterCutoffs} historyCuts=${stats.historyCutoffs} " +
      s"continuationCuts=${stats.continuationCutoffs} captureHistoryCuts=${stats.captureHistoryCutoffs}"
    )

  private def logTimeProfile(p: TimeProfile, nodes: Long): Unit =
    val total = math.max(1L, p.totalNs)
    val timePerNodeUs =
      if nodes > 0 then f"${total.toDouble / nodes.toDouble / 1000.0}%.3f"
      else "n/a"
    def ms(ns: Long): String = f"${ns.toDouble / 1000000.0}%.3f"
    println(
      s"[AI][TIME] totalMs=${ms(total)} nodes=$nodes timePerNodeUs=$timePerNodeUs " +
      s"searchMs=${ms(p.searchNs)} evalMs=${ms(p.evalNs)} evalHCEMs=${ms(p.evalHceNs)} " +
      s"orderMs=${ms(p.orderMovesNs)} moveGenMs=${ms(p.moveGenNs)} applyMoveMs=${ms(p.applyMoveNs)} statusMs=${ms(p.statusNs)} " +
      s"ttMs=${ms(p.ttNs)} heuristicsMs=${ms(p.heuristicNs)} openingDbMs=${ms(p.openingLookupNs)} tablebaseDbMs=${ms(p.tablebaseLookupNs)}"
    )

  private def evictIfNeeded[K, T](
    map: ConcurrentHashMap[K, T],
    queue: ConcurrentLinkedQueue[K],
    count: AtomicLong,
    maxEntries: Int
  ): Unit =
    var loops = 0
    while count.get() > maxEntries && loops < 10_000 do
      val victim = queue.poll()
      if victim != null then
        val removed = map.remove(victim)
        if removed != null then count.decrementAndGet()
      loops += 1

  private def lookupOpeningMove(state: GameState, legalMoves: List[Move]): Option[OpeningHit] =
    persistenceOpt
      .flatMap(_.openingDao.findBestByFen(state.toFen).unsafeRunSync())
      .flatMap(o =>
        CoordinateMoveParser.parse(o.move, state).toOption
          .filter(legalMoves.contains)
          .map(m => OpeningHit(m, o.weight))
      )

  private def lookupTablebaseMove(state: GameState, legalMoves: List[Move]): Option[TablebaseHit] =
    if !canUseTablebase(state) then None
    else
      SyzygyProbe.probe(state)
        .map(hit => TablebaseHit(hit.move, hit.wdl, hit.dtz, hit.dtm, "SYZYGY"))
        .filter(hit => legalMoves.contains(hit.move))
        .orElse(
          persistenceOpt
            .flatMap(_.tablebaseDao.findEntryByFen(state.toFen).unsafeRunSync())
            .flatMap(e =>
              CoordinateMoveParser.parse(e.bestMove, state).toOption
                .filter(legalMoves.contains)
                .map(m => TablebaseHit(m, e.wdl, e.dtz, e.dtm, "TABLEBASE_DB"))
            )
        )
