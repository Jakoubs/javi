package chess.ai2.mcts

import chess.ai2.core.{SearchContext, SearchLimits, SearchResult}
import chess.ai2.nn.PolicyValueNet
import chess.model.{GameRules, GameState, GameStatus, Move, MoveGenerator}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.util.Random

final case class MctsConfig(
  cpuct: Double = 1.5,
  rootDirichletAlpha: Double = 0.3,
  rootDirichletEpsilon: Double = 0.25,
  maxDepthHint: Int = 64,
  simulationDepth: Int = 64,
  workerCount: Int = math.max(1, Runtime.getRuntime.availableProcessors() - 1),
  virtualLossValue: Double = 1.0,
  ttMinVisits: Int = 24,
  ponderMaxNodes: Int = 300000
)

class MctsSearch(
  net: PolicyValueNet,
  tt: TranspositionTable,
  config: MctsConfig = MctsConfig()
):
  private val rng = new Random()
  private val virtualLossUnits: Int = math.max(1, math.round(config.virtualLossValue).toInt)
  @volatile private var previousRoot: Option[MctsNode] = None
  @volatile private var ponderThread: Option[Thread] = None
  private val ponderStop = AtomicBoolean(false)

  def search(state: GameState, limits: SearchLimits, ctx: SearchContext): SearchResult =
    stopPonder()
    val t0 = System.nanoTime()

    val legalMoves = MoveGenerator.legalMoves(state)
    if legalMoves.isEmpty then return SearchResult(None, Nil, 0, 0L, 0)

    val root = acquireRoot(state, ctx)
    ensureExpanded(root, legalMoves, isRoot = true)

    val deadlineNs = System.nanoTime() + limits.hardTimeMs * 1000000L
    val nodes = AtomicLong(0L)
    val ttHits = AtomicLong(0L)
    runParallel(root, deadlineNs, limits.maxNodes, nodes, ttHits, () => false)

    val rootChildren = root.childrenArray
    val bestOpt = if rootChildren.isEmpty then None else Some(rootChildren.maxBy(_._2.edge.visits.get()))

    val result = bestOpt.map(_._1)
    val score = bestOpt.map(e => (e._2.edge.q * 100.0).toInt).getOrElse(0)
    previousRoot = Some(root)

    if root.visits.get() >= config.ttMinVisits then
      tt.put(TranspositionEntry(root.key, root.visits.get(), score / 100.0, result, config.simulationDepth))

    val elapsedMs = ((System.nanoTime() - t0) / 1000000L).max(1L)
    val nps = (nodes.get() * 1000L) / elapsedMs

    SearchResult(
      bestMove = result,
      principalVariation = result.toList,
      scoreCp = score,
      nodes = nodes.get(),
      depth = config.simulationDepth,
      elapsedMs = elapsedMs,
      nps = nps,
      ttHits = ttHits.get()
    )

  def startPonder(state: GameState, ctx: SearchContext): Unit =
    stopPonder()
    val root = acquireRoot(state, ctx)
    val legal = MoveGenerator.legalMoves(state)
    if legal.nonEmpty then ensureExpanded(root, legal, isRoot = true)

    ponderStop.set(false)
    val t = Thread(() =>
      val nodes = AtomicLong(0L)
      val ttHits = AtomicLong(0L)
      val deadlineNs = System.nanoTime() + 60L * 1000000000L
      runParallel(root, deadlineNs, config.ponderMaxNodes, nodes, ttHits, () => ponderStop.get())
      previousRoot = Some(root)
    , "mcts-ponder")
    t.setDaemon(true)
    ponderThread = Some(t)
    t.start()

  def stopPonder(): Unit =
    ponderStop.set(true)
    ponderThread.foreach { t =>
      t.join(200L)
      if t.isAlive then
        t.interrupt()
        t.join(800L)
    }
    ponderThread = None

  private def runParallel(root: MctsNode, deadlineNs: Long, maxNodes: Int, nodes: AtomicLong, ttHits: AtomicLong, stop: () => Boolean): Unit =
    val workers = new Array[Thread](config.workerCount)
    var i = 0
    while i < workers.length do
      workers(i) = Thread(() => runWorker(root, deadlineNs, maxNodes, nodes, ttHits, stop), s"mcts-worker-$i")
      workers(i).setDaemon(true)
      workers(i).start()
      i += 1

    i = 0
    while i < workers.length do
      workers(i).join()
      i += 1

  private def runWorker(root: MctsNode, deadlineNs: Long, maxNodes: Int, nodes: AtomicLong, ttHits: AtomicLong, stop: () => Boolean): Unit =
    while !stop() && System.nanoTime() < deadlineNs do
      val budgetTicket = nodes.incrementAndGet()
      if budgetTicket > maxNodes then
        nodes.decrementAndGet()
        return

      val path = scala.collection.mutable.ArrayBuffer.empty[(MctsNode, Move, MctsChild)]
      var node = root
      var depth = 0

      while depth < config.simulationDepth && node.expanded.get() && !node.terminal.get() do
        selectChild(node) match
          case None => depth = config.simulationDepth
          case Some((mv, child)) =>
            child.edge.virtualLoss.addAndGet(virtualLossUnits)
            path += ((node, mv, child))
            node = child.node
            depth += 1

      val leafValue = evaluateLeaf(node, depth, ttHits)
      backprop(path, leafValue)

  private def backprop(path: scala.collection.mutable.ArrayBuffer[(MctsNode, Move, MctsChild)], leafValue: Double): Unit =
    var value = leafValue
    var i = path.length - 1
    while i >= 0 do
      val (_, _, child) = path(i)
      child.edge.virtualLoss.addAndGet(-virtualLossUnits)
      child.edge.visits.incrementAndGet()
      child.edge.addValue(value)
      child.node.visits.incrementAndGet()

      if child.node.visits.get() >= config.ttMinVisits then
        tt.put(TranspositionEntry(child.node.key, child.node.visits.get(), value, Some(child.edge.move), config.simulationDepth - i))

      value = -value
      i -= 1

  private def evaluateLeaf(node: MctsNode, depth: Int, ttHits: AtomicLong): Double =
    tt.get(node.key) match
      case Some(hit) if hit.visits >= config.ttMinVisits && hit.depthHint >= depth =>
        ttHits.incrementAndGet()
        hit.value
      case _ =>
        GameRules.computeStatus(node.state) match
          case GameStatus.Checkmate(loser) => if loser == node.state.activeColor then -1.0 else 1.0
          case GameStatus.Stalemate | GameStatus.Draw(_) => 0.0
          case _ =>
            val legal = MoveGenerator.legalMoves(node.state)
            if legal.isEmpty then 0.0
            else
              ensureExpanded(node, legal)
              net.evaluate(node.state, legal).value

  private def ensureExpanded(node: MctsNode, legalMoves: List[Move], isRoot: Boolean = false): Unit =
    if node.expanded.compareAndSet(false, true) then
      val eval = net.evaluate(node.state, legalMoves)
      val priors = eval.priors
      val arr = new Array[(Move, MctsChild)](legalMoves.size)
      var i = 0
      legalMoves.foreach { move =>
        val p = priors.getOrElse(move, 1e-6)
        val next = GameRules.applyMove(node.state, move)
        val childKey = Zobrist.nextKey(node.state, node.key, next)
        arr(i) = (move, MctsChild(MctsEdge(move, p), MctsNode(next, childKey)))
        i += 1
      }
      if isRoot then applyRootDirichletNoise(arr)
      node.setChildren(arr)
      if legalMoves.isEmpty then node.terminal.set(true)

  private def applyRootDirichletNoise(children: Array[(Move, MctsChild)]): Unit =
    if children.nonEmpty && config.rootDirichletEpsilon > 0.0 then
      val eps = math.max(0.0, math.min(1.0, config.rootDirichletEpsilon))
      val alpha = math.max(1e-3, config.rootDirichletAlpha)
      val noise = Array.ofDim[Double](children.length)
      var sum = 0.0
      var i = 0
      while i < children.length do
        val n = sampleGamma(alpha)
        noise(i) = n
        sum += n
        i += 1

      if sum > 0.0 then
        i = 0
        while i < children.length do
          val child = children(i)._2
          val mixed = (1.0 - eps) * child.edge.prior + eps * (noise(i) / sum)
          child.edge.setPrior(math.max(1e-8, mixed))
          i += 1

  private def sampleGamma(shape: Double): Double =
    if shape < 1.0 then
      sampleGamma(shape + 1.0) * math.pow(rng.nextDouble(), 1.0 / shape)
    else
      val d = shape - 1.0 / 3.0
      val c = 1.0 / math.sqrt(9.0 * d)
      var accepted = false
      var x = 0.0
      var v = 0.0
      while !accepted do
        x = rng.nextGaussian()
        val t = 1.0 + c * x
        if t > 0.0 then
          v = t * t * t
          val u = rng.nextDouble()
          if u < 1.0 - 0.0331 * x * x * x * x || math.log(u) < 0.5 * x * x + d * (1.0 - v + math.log(v)) then
            accepted = true
      d * v

  private def selectChild(node: MctsNode): Option[(Move, MctsChild)] =
    val arr = node.childrenArray
    if arr.isEmpty then None
    else
      val parentVisits = math.max(1, node.visits.get())
      val sqrtParent = math.sqrt(parentVisits.toDouble)

      var bestMove = arr(0)._1
      var bestChild = arr(0)._2
      var bestScore = Double.NegativeInfinity
      var i = 0
      while i < arr.length do
        val (mv, child) = arr(i)
        val edge = child.edge
        val n = edge.visits.get() + edge.virtualLoss.get()
        val ttBonus = tt.get(child.node.key).map(_.value * 0.1).getOrElse(0.0)
        val q = edge.q + ttBonus
        val u = config.cpuct * edge.prior * (sqrtParent / (1.0 + n.toDouble))
        val score = q + u
        if score > bestScore then
          bestScore = score
          bestMove = mv
          bestChild = child
        i += 1

      Some((bestMove, bestChild))

  private def acquireRoot(state: GameState, ctx: SearchContext): MctsNode =
    val fromReuse = for
      prev <- previousRoot
      last <- ctx.lastOpponentMove
      child <- prev.childForMove(last)
      if child.node.state.toFen == state.toFen
    yield child.node

    fromReuse.getOrElse(MctsNode(state, Zobrist.key(state)))

