package chess.ai

import cats.effect.unsafe.implicits.global
import chess.ai.nn.{HceBootstrappedPolicyValueNet, OnnxValueNet, PolicyValueEvaluation, PolicyValueNet}
import chess.model.*
import chess.persistence.PersistenceModule
import chess.util.parser.CoordinateMoveParser
import java.util.SplittableRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

import scala.util.Try
import scala.util.boundary

/**
 * Minimal Alpha-Beta engine with:
 * - opening DB shortcut
 * - NN static evaluation
 * - NN priors for move ordering
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

  private enum EvalMode:
    case Hce, Nn, Blend, RootNnTieBreak

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
  private val AspirationInitialWindow = 1.2
  private val MaxOpeningBookPly =
    sys.env.get("CHESS_OPENING_MAX_PLY").flatMap(_.toIntOption).getOrElse(16)
  private val StalemateAvoidMaterialCp = 300
  private val RootDrawPolicyThreshold = 0.7
  private val RootDrawPolicyBias = 0.35
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
  private val NnBlendWeight =
    sys.env.get("CHESS_NN_BLEND_WEIGHT").flatMap(_.toDoubleOption).getOrElse(0.2).max(0.0).min(1.0)
  private val RootNnTieBreakWindow =
    sys.env.get("CHESS_ROOT_NN_TIEBREAK_WINDOW").flatMap(_.toDoubleOption).getOrElse(0.35).max(0.0)
  private val EvalModeSetting =
    sys.env.get("CHESS_EVAL_MODE").map(_.trim.toLowerCase) match
      case Some("nn") => EvalMode.Nn
      case Some("blend") => EvalMode.Blend
      case Some(mode) if Set("root-nn", "nn-root", "root-tiebreak", "nn-tiebreak").contains(mode) =>
        EvalMode.RootNnTieBreak
      case _ => EvalMode.Hce
  private val tt = new ConcurrentHashMap[Long, TTEntry]()
  private val ttEvictionQueue = new ConcurrentLinkedQueue[Long]()
  private val ttStamp = new AtomicLong(0L)
  private val ttCount = new AtomicLong(0L)
  private val MaxTtEntries = 300_000
  private val statusCache = new ConcurrentHashMap[Long, GameStatus]()
  private val statusEvictionQueue = new ConcurrentLinkedQueue[Long]()
  private val statusCount = new AtomicLong(0L)
  private val MaxStatusEntries = 200_000
  private val moveCache = new ConcurrentHashMap[Long, List[Move]]()
  private val moveEvictionQueue = new ConcurrentLinkedQueue[Long]()
  private val moveCount = new AtomicLong(0L)
  private val MaxMoveEntries = 300_000
  private val evalCache = new ConcurrentHashMap[Long, PolicyValueEvaluation]()
  private val evalEvictionQueue = new ConcurrentLinkedQueue[Long]()
  private val evalCount = new AtomicLong(0L)
  private val MaxEvalEntries = 300_000

  private final case class TTEntry(score: Double, depth: Int, bound: Int, best: Option[Move], stamp: Long)
  private val killers: Array[Array[Move | Null]] = Array.fill(MaxSearchDepth + 1, 2)(null)
  private val history: Array[Array[Array[Int]]] = Array.fill(2, 64, 64)(0)
  private val counterMoves: Array[Array[Array[Move | Null]]] = Array.fill(2, 64, 64)(null)
  private final case class SearchStats(
    betaCutoffs: Long = 0L,
    heuristicCutoffs: Long = 0L,
    heuristicSavedNodesEstimate: Double = 0.0,
    killerCutoffs: Long = 0L,
    counterCutoffs: Long = 0L,
    historyCutoffs: Long = 0L
  )
  private final case class TimeProfile(
    var totalNs: Long = 0L,
    var tablebaseLookupNs: Long = 0L,
    var openingLookupNs: Long = 0L,
    var searchNs: Long = 0L,
    var orderMovesNs: Long = 0L,
    var evalNs: Long = 0L,
    var evalNnNs: Long = 0L,
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

  private val zobristRandom = new SplittableRandom(0x1A2B3C4D5E6F7788L)
  private def nextZobrist(): Long =
    val x = zobristRandom.nextLong()
    if x != 0L then x else 0x9E3779B97F4A7C15L

  private val statusFiftyMoveZobrist: Long = nextZobrist()
  private val statusRepetitionZobrist: Array[Long] = Array.fill(4)(nextZobrist())
  private val evalNamespaceZobrist: Map[String, Long] = Map(
    "nn" -> nextZobrist(),
    "hce" -> nextZobrist(),
    "hce_fast" -> nextZobrist(),
    "root_hce" -> nextZobrist(),
    "root_nn" -> nextZobrist()
  )

  private lazy val persistenceOpt =
    Try {
      PersistenceModule.build().unsafeRunSync()
    }.toOption

  private lazy val policyNet: PolicyValueNet =
    loadPolicyNet()
  private lazy val hceNet: PolicyValueNet =
    HceBootstrappedPolicyValueNet.default
  private lazy val fastHceNet: PolicyValueNet =
    HceBootstrappedPolicyValueNet.fast

  private def loadPolicyNet(): PolicyValueNet =
    val defaultPath = "D:\\SoftwareArchitektur\\javi\\data\\nexus_nano.onnx"
    val path = sys.env.getOrElse("CHESS_NN_MODEL_PATH", defaultPath)
    Try {
      println(s"[AI][NN] loading model from '$path'")
      OnnxValueNet.fromPath(path)
    }.fold(
      err =>
        println(s"[AI][NN] load failed for '$path' -> fallback HCE net (${err.getMessage})")
        HceBootstrappedPolicyValueNet.default,
      identity
    )

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
      else state.copy(history = Nil, repetitionCounts = state.repetitionCounts)
    val profile = TimeProfile()
    val startedAll = System.nanoTime()
    val legalMoves = cachedLegalMoves(searchState, positionHash(searchState), profile)
    if legalMoves.isEmpty then return None
    if legalMoves.size == 1 then return legalMoves.headOption.map(move => SearchResult(move, 0.0, 0, 0L))

    if searchState.board.pieceCount <= SyzygyProbe.maxPieces then
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
    if mode == SearchMode.Search then
      bookMove.foreach { hit =>
        println(s"[AI][MOVE] source=OPENING_DB move=${hit.move.toInputString} weight=${hit.weight}")
      }
    if bookMove.nonEmpty then
      if logFinalMove then
        profile.totalNs = System.nanoTime() - startedAll
        logTimeProfile(profile, nodes = 0L)
      return bookMove.map(hit => SearchResult(hit.move, 0.0, 0, 0L))

    val start = System.currentTimeMillis()
    val deadline = start + math.max(1L, timeLimitMs)

    var best: Option[Move] = None
    var lastComplete: Option[Move] = None
    var lastScoreCp = 0.0
    var completedDepth = 0
    var completedNodes = 0L
    var completedStats = SearchStats()
    var depth = 1

    val searchStart = System.nanoTime()
    boundary:
      while depth <= MaxSearchDepth do
        if System.currentTimeMillis() >= deadline - 10 then boundary.break()
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
            best = Some(rs.move)
            lastComplete = Some(rs.move)
            lastScoreCp = rs.scoreCp
            completedDepth = depth
            if mode == SearchMode.Search then
              val evalPawnsText = f"${rs.scoreCp}%.3f"
              println(
                s"[AI][SEARCH] depth=$depth nodes=${rs.nodes} timeMs=${rs.elapsedMs} evalPawns=$evalPawnsText pv=${rs.move.toInputString} " +
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
    val ttMoveRoot = Option(tt.get(searchHash(state))).flatMap(_.best)
    val ordered = orderMoves(state, legalMoves, depth, None, ttMoveRoot, profile)
    var alpha = startAlpha
    val beta = startBeta
    var bestMove: Option[Move] = None
    var bestScore = Double.NegativeInfinity
    val rootScores = scala.collection.mutable.ListBuffer.empty[(Move, Double)]
    val repetitionFlags = ordered.map(m => wouldCauseImmediateThreefold(state, m))
    val materialLeadCp = materialLeadCpFor(state, state.activeColor)
    val stalemateFlags =
      if materialLeadCp >= StalemateAvoidMaterialCp then ordered.map(m => wouldCauseImmediateStalemate(state, m))
      else ordered.map(_ => false)
    val rootDrawScore = drawScoreForRoot(rootStaticScore(state, legalMoves, profile))

    for (move, idx) <- ordered.zipWithIndex do
      if System.currentTimeMillis() >= deadline then boundary.break(None)
      val isRepetitionMove = repetitionFlags(idx)
      val isStalemateMove = stalemateFlags(idx)
      val score =
        if isRepetitionMove || isStalemateMove then
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
      rootScores += move -> score

    val elapsed = System.currentTimeMillis() - started
    val picked = rootNnTieBreak(state, rootScores.toList, bestScore, deadline, profile).orElse(bestMove)
    picked.map { move =>
      val pickedScore = rootScores.find(_._1 == move).map(_._2).getOrElse(bestScore)
      val rootScore = sanitizeScore(bestScore)
      val bound =
        if rootScore <= startAlpha then 2
        else if rootScore >= startBeta then 1
        else 0
      val ttPutStart = System.nanoTime()
      putTt(
        searchHash(state),
        TTEntry(scoreToTt(rootScore, plyFromRoot = 0), depth, bound, Some(move), ttStamp.incrementAndGet())
      )
      profile.ttNs += (System.nanoTime() - ttPutStart)
      RootSearch(move, sanitizeScore(pickedScore), nodes(0), elapsed, stats(0))
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

    if state.board.pieceCount <= SyzygyProbe.maxPieces then
      val tbStart = System.nanoTime()
      val tbScore = SyzygyProbe.probeWdl(state).map(tablebaseScore)
      profile.tablebaseLookupNs += (System.nanoTime() - tbStart)
      tbScore.foreach(score => boundary.break(score))

    val legalKey = positionHash(state)
    val positionKey = searchHash(state)
    val ttStart = System.nanoTime()
    val ttHit = tt.get(positionKey)
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

    // Null-move pruning (safe guards to reduce zugzwang/pathological risk).
    val inCheck = MoveGenerator.isInCheck(state, state.activeColor)
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
    val ordered = orderMoves(state, legalMoves, depth, prevMove, Option(ttHit).flatMap(_.best), profile)
    val nodeStartAtThisPosition = nodes(0)
    var bestSeen: Option[Move] = None
    var moveIndex = 0

    for move <- ordered do
      if System.currentTimeMillis() >= deadline then boundary.break(currentAlpha)
      val amStart = System.nanoTime()
      val child = fastApplyMove(state, move)
      profile.applyMoveNs += (System.nanoTime() - amStart)
      val quietMove = !isCapture(state, move) && move.promotion.isEmpty
      val useLmr =
        depth >= 3 &&
        moveIndex >= 3 &&
        quietMove &&
        !inCheck
      val score =
        if useLmr then
          val reducedDepth = (depth - 1 - LmrReduction).max(0)
          val reduced = sanitizeScore(-negamax(
            child,
            reducedDepth,
            -currentAlpha - 1,
            -currentAlpha,
            deadline,
            nodes,
            stats,
            Some(move),
            profile,
            allowNullMove = true,
            plyFromRoot = plyFromRoot + 1
          ))
          if reduced > currentAlpha then
            sanitizeScore(-negamax(child, depth - 1, -beta, -currentAlpha, deadline, nodes, stats, Some(move), profile, allowNullMove = true, plyFromRoot = plyFromRoot + 1))
          else reduced
        else
          sanitizeScore(-negamax(child, depth - 1, -beta, -currentAlpha, deadline, nodes, stats, Some(move), profile, allowNullMove = true, plyFromRoot = plyFromRoot + 1))
      if score > currentAlpha then
        currentAlpha = score
        bestSeen = Some(move)
      if currentAlpha >= beta then
        val c = colorIndex(state.activeColor)
        val from = squareIndex(move.from)
        val to = squareIndex(move.to)
        val d = depth.max(0).min(MaxSearchDepth)
        val isKiller =
          !isCapture(state, move) && ((killers(d)(0) != null && killers(d)(0) == move) || (killers(d)(1) != null && killers(d)(1) == move))
        val isCounter =
          !isCapture(state, move) && prevMove.exists { pm =>
            val pFrom = squareIndex(pm.from)
            val pTo = squareIndex(pm.to)
            val cm = counterMoves(c)(pFrom)(pTo)
            cm != null && cm == move
          }
        val hasHistory = !isCapture(state, move) && history(c)(from)(to) > 0
        val isHeuristicCutoff = isKiller || isCounter || hasHistory
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
          historyCutoffs = old.historyCutoffs + (if hasHistory then 1 else 0)
        )
        val heuStart = System.nanoTime()
        if !isCapture(state, move) then
          updateKillers(depth, move)
          updateHistory(state.activeColor, move, depth)
          updateCounterMove(state.activeColor, prevMove, move)
        profile.heuristicNs += (System.nanoTime() - heuStart)
        boundary.break(currentAlpha)
      moveIndex += 1

    currentAlpha = sanitizeScore(currentAlpha)
    val bound =
      if currentAlpha <= originalAlpha then 2
      else if currentAlpha >= beta then 1
      else 0
    val ttPutStart = System.nanoTime()
    putTt(positionKey, TTEntry(scoreToTt(sanitizeScore(currentAlpha), plyFromRoot), depth, bound, bestSeen, ttStamp.incrementAndGet()))
    profile.ttNs += (System.nanoTime() - ttPutStart)
    currentAlpha

  private def staticEvalCp(
    state: GameState,
    legalMoves: List[Move],
    profile: TimeProfile,
    depth: Int,
    alpha: Double,
    beta: Double
  ): Double =
    val eval = selectiveEvaluate(state, legalMoves, profile, depth, alpha, beta)
    eval.value * ValueScale

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
    if !inCheck then
      val standPat = staticEvalCp(state, Nil, profile, -qDepth, alpha, beta)
      if standPat >= beta then boundary.break(beta)
      if standPat > currentAlpha then currentAlpha = standPat
      if qDepth >= MaxQuiescenceDepth then boundary.break(sanitizeScore(currentAlpha))

    val moves =
      if inCheck then
        val legalMoves = cachedLegalMoves(state, positionHash(state), profile)
        if legalMoves.isEmpty then boundary.break(matedScore(plyFromRoot))
        if qDepth >= MaxQuiescenceDepth then
          boundary.break(staticEvalCp(state, legalMoves, profile, -qDepth, alpha, beta))
        legalMoves
      else
        val noisy = noisyMoves(state, profile)
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
    legalMoves: List[Move],
    profile: TimeProfile
  ): Double =
    safeNetEvaluate(hceNet, state, legalMoves, profile, isNn = false, cacheNamespace = "root_hce").value * ValueScale

  private def drawScoreForRoot(rootScore: Double): Double =
    if rootScore > RootDrawPolicyThreshold then -RootDrawPolicyBias
    else if rootScore < -RootDrawPolicyThreshold then RootDrawPolicyBias
    else 0.0

  private def rootNnTieBreak(
    state: GameState,
    rootScores: List[(Move, Double)],
    bestScore: Double,
    deadline: Long,
    profile: TimeProfile
  ): Option[Move] =
    if EvalModeSetting != EvalMode.RootNnTieBreak || rootScores.size <= 1 then None
    else
      val candidates = rootScores.filter { case (_, score) =>
        bestScore - score <= RootNnTieBreakWindow
      }
      if candidates.size <= 1 then None
      else
        var bestMove: Option[Move] = None
        var bestNnScore = Double.NegativeInfinity
        candidates.foreach { case (move, _) =>
          if System.currentTimeMillis() < deadline then
            val child = fastApplyMove(state, move)
            val nnScore = -safeNetEvaluate(policyNet, child, Nil, profile, isNn = true, cacheNamespace = "root_nn").value
            if nnScore > bestNnScore then
              bestNnScore = nnScore
              bestMove = Some(move)
        }
        bestMove

  private def selectiveEvaluate(
    state: GameState,
    legalMoves: List[Move],
    profile: TimeProfile,
    depth: Int,
    alpha: Double,
    beta: Double
  ): PolicyValueEvaluation =
    EvalModeSetting match
      case EvalMode.Hce | EvalMode.RootNnTieBreak =>
        hceEvaluate(state, legalMoves, profile, depth)
      case EvalMode.Nn =>
        safeNetEvaluate(policyNet, state, legalMoves, profile, isNn = true, cacheNamespace = "nn")
      case EvalMode.Blend =>
        blendedEvaluate(state, legalMoves, profile, depth)

  private def hceEvaluate(
    state: GameState,
    legalMoves: List[Move],
    profile: TimeProfile,
    depth: Int
  ): PolicyValueEvaluation =
    val inCheck = MoveGenerator.isInCheck(state, state.activeColor)
    val pieceCount = state.board.pieceCount
    val useFastHce = depth <= FastHceAtOrBelowDepth && pieceCount > 10 && !inCheck
    val net = if useFastHce then fastHceNet else hceNet
    safeNetEvaluate(net, state, legalMoves, profile, isNn = false, cacheNamespace = if useFastHce then "hce_fast" else "hce")

  private def blendedEvaluate(
    state: GameState,
    legalMoves: List[Move],
    profile: TimeProfile,
    depth: Int
  ): PolicyValueEvaluation =
    val hce = hceEvaluate(state, legalMoves, profile, depth)
    val nn = safeNetEvaluate(policyNet, state, legalMoves, profile, isNn = true, cacheNamespace = "nn")
    hce.copy(
      priors = if nn.priors.nonEmpty then nn.priors else hce.priors,
      value = ((1.0 - NnBlendWeight) * hce.value) + (NnBlendWeight * nn.value),
      uncertainty = math.max(hce.uncertainty, nn.uncertainty)
    )

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
      val out = legalMoves.sortBy(m => -moveOrderingScore(state, state.board, m, depth, prevMove, ttMove))
      profile.orderMovesNs += (System.nanoTime() - started)
      out

  private def moveOrderingScore(
    state: GameState,
    board: Board,
    move: Move,
    depth: Int,
    prevMove: Option[Move],
    ttMove: Option[Move]
  ): Int =
    val ttScore = if ttMove.contains(move) then 200_000 else 0
    val attacker = board.pieceAtOrNull(move.from)
    val victim = board.pieceAtOrNull(move.to)
    val attackerValue = if attacker == null then 0 else PieceType.pieceValue(attacker.pieceType)
    val victimValue = if victim == null then 0 else PieceType.pieceValue(victim.pieceType)
    val captureScore =
      if isCapture(board, state.enPassantTarget, move) then 100_000 + (10 * victimValue) - attackerValue
      else 0
    val promotionScore = move.promotion match
      case Some(PieceType.Queen) => 39_000
      case Some(PieceType.Rook) => 35_000
      case Some(PieceType.Bishop) => 33_000
      case Some(PieceType.Knight) => 34_000
      case Some(PieceType.Pawn | PieceType.King) => 30_000
      case None => 0

    val d = depth.max(0).min(MaxSearchDepth)
    val killerScore =
      if !isCapture(state, move) then
        val k1 = killers(d)(0)
        val k2 = killers(d)(1)
        if k1 != null && move == k1 then 20_000
        else if k2 != null && move == k2 then 15_000
        else 0
      else 0

    val c = colorIndex(state.activeColor)
    val from = squareIndex(move.from)
    val to = squareIndex(move.to)
    val historyScore = history(c)(from)(to).min(12_000)

    val counterScore =
      if !isCapture(state, move) then
        prevMove match
          case Some(pm) =>
            val pFrom = squareIndex(pm.from)
            val pTo = squareIndex(pm.to)
            val cm = counterMoves(c)(pFrom)(pTo)
            if cm != null && move == cm then 25_000 else 0
          case None => 0
      else 0

    val defensiveScore = defensiveModeOrderingScore(state, board, move, attacker, captureScore > 0)

    ttScore + captureScore + promotionScore + killerScore + counterScore + historyScore + defensiveScore

  private def defensiveModeOrderingScore(
    state: GameState,
    board: Board,
    move: Move,
    attacker: Piece | Null,
    isCaptureMove: Boolean
  ): Int =
    val ownKingPosOpt = state.kingPos(state.activeColor)
    if ownKingPosOpt.isEmpty then 0
    else
      val ownKingPos = ownKingPosOpt.get
      val danger = kingDangerForOrdering(board, ownKingPos, state.activeColor)
      if danger < 18 then 0
      else
        val quietQueenMove =
          attacker != null &&
          attacker.pieceType == PieceType.Queen &&
          !isCaptureMove &&
          move.promotion.isEmpty &&
          !givesCheck(state, move)
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
              case Piece(enemyColor, PieceType.Queen | PieceType.Rook | PieceType.Knight) if enemyColor == state.activeColor.opposite =>
                8_000
              case Piece(enemyColor, PieceType.Bishop) if enemyColor == state.activeColor.opposite && chebyshevDistance(move.to, ownKingPos) <= 2 =>
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
    state.board.foreachPiece {
      case (pos, Piece(`color`, PieceType.Pawn)) =>
        addNoisyPawnMoves(out, state, pos, color)
      case (pos, Piece(`color`, PieceType.Knight)) =>
        addNoisyKnightMoves(out, state, pos, color)
      case (pos, Piece(`color`, PieceType.Bishop)) =>
        addNoisySlidingMoves(out, state, pos, color, NoisyBishopDirections)
      case (pos, Piece(`color`, PieceType.Rook)) =>
        addNoisySlidingMoves(out, state, pos, color, NoisyRookDirections)
      case (pos, Piece(`color`, PieceType.Queen)) =>
        addNoisySlidingMoves(out, state, pos, color, NoisyQueenDirections)
      case (pos, Piece(`color`, PieceType.King)) =>
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
    var i = 0
    while i < NoisyKnightDeltas.length do
      val (dc, dr) = NoisyKnightDeltas(i)
      val to = pos + (dc, dr)
      if to.isValid && state.board.isOccupiedBy(to, color.opposite) then out += Move(pos, to)
      i += 1

  private def addNoisySlidingMoves(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    pos: Pos,
    color: Color,
    directions: Array[(Int, Int)]
  ): Unit =
    var dirIndex = 0
    while dirIndex < directions.length do
      val (dc, dr) = directions(dirIndex)
      var to = pos + (dc, dr)
      var blocked = false
      while to.isValid && !blocked do
        state.board.get(to) match
          case None =>
            to = to + (dc, dr)
          case Some(Piece(c, _)) if c == color.opposite =>
            out += Move(pos, to)
            blocked = true
          case _ =>
            blocked = true
      dirIndex += 1

  private def addNoisyKingMoves(
    out: scala.collection.mutable.ArrayBuffer[Move],
    state: GameState,
    pos: Pos,
    color: Color
  ): Unit =
    var i = 0
    while i < NoisyKingDirections.length do
      val (dc, dr) = NoisyKingDirections(i)
      val to = pos + (dc, dr)
      if to.isValid && state.board.isOccupiedBy(to, color.opposite) then out += Move(pos, to)
      i += 1

  private def isLegalNoisyMove(state: GameState, move: Move): Boolean =
    state.board.get(move.to).forall(_.pieceType != PieceType.King) &&
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
        GameState.black(b, cr, None, nextHalf, fm, Nil, positionHash = nextHash, repetitionCounts = nextRepetitionCounts)
      case BlackToMove(b, cr, _, _, fm, _, _, _, _, _, _) =>
        GameState.white(b, cr, None, nextHalf, fm + 1, Nil, positionHash = nextHash, repetitionCounts = nextRepetitionCounts)

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
        Nil,
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
        Nil,
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
          j += 1
        i += 1
      c += 1

  private def putTt(key: Long, entry: TTEntry): Unit =
    val previous = tt.put(key, entry)
    if previous == null then ttCount.incrementAndGet()
    ttEvictionQueue.add(key)
    evictIfNeeded(tt, ttEvictionQueue, ttCount, MaxTtEntries)

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

  private def safeNetEvaluate(
    net: PolicyValueNet,
    state: GameState,
    legalMoves: List[Move],
    profile: TimeProfile,
    isNn: Boolean,
    cacheNamespace: String
  ): PolicyValueEvaluation =
    val started = System.nanoTime()
    val cacheKey = evalHash(state, cacheNamespace)
    val cached = evalCache.get(cacheKey)
    val out =
      if cached != null then cached
      else
        val computed = Try(net.evaluate(state, legalMoves)).getOrElse(hceNet.evaluate(state, legalMoves))
        val existing = evalCache.putIfAbsent(cacheKey, computed)
        if existing != null then existing
        else
          evalCount.incrementAndGet()
          evalEvictionQueue.add(cacheKey)
          evictIfNeeded(evalCache, evalEvictionQueue, evalCount, MaxEvalEntries)
          computed
    val elapsed = System.nanoTime() - started
    profile.evalNs += elapsed
    if isNn then profile.evalNnNs += elapsed else profile.evalHceNs += elapsed
    out

  private def mergeStats(a: SearchStats, b: SearchStats): SearchStats =
    SearchStats(
      betaCutoffs = a.betaCutoffs + b.betaCutoffs,
      heuristicCutoffs = a.heuristicCutoffs + b.heuristicCutoffs,
      heuristicSavedNodesEstimate = a.heuristicSavedNodesEstimate + b.heuristicSavedNodesEstimate,
      killerCutoffs = a.killerCutoffs + b.killerCutoffs,
      counterCutoffs = a.counterCutoffs + b.counterCutoffs,
      historyCutoffs = a.historyCutoffs + b.historyCutoffs
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
      s"heuristicShareOfCutoffs=$heuristicShareText savedNodesEstimate=$savedNodesEstimate savedNodesPct=$savedNodesPctText"
    )

  private def logTimeProfile(p: TimeProfile, nodes: Long): Unit =
    val total = math.max(1L, p.totalNs)
    val timePerNodeUs =
      if nodes > 0 then f"${total.toDouble / nodes.toDouble / 1000.0}%.3f"
      else "n/a"
    def ms(ns: Long): String = f"${ns.toDouble / 1000000.0}%.3f"
    println(
      s"[AI][TIME] totalMs=${ms(total)} nodes=$nodes timePerNodeUs=$timePerNodeUs " +
      s"searchMs=${ms(p.searchNs)} evalMs=${ms(p.evalNs)} evalNNMs=${ms(p.evalNnNs)} evalHCEMs=${ms(p.evalHceNs)} " +
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
