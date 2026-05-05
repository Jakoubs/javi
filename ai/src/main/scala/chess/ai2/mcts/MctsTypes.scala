package chess.ai2.mcts

import chess.model.{Color, GameState, Move, PieceType}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}

final class MctsEdge(val move: Move, initialPrior: Double):
  val visits = AtomicInteger(0)
  val valueSum = AtomicLong(java.lang.Double.doubleToRawLongBits(0.0))
  val virtualLoss = AtomicInteger(0)
  @volatile private var priorValue: Double = initialPrior

  def prior: Double = priorValue
  def setPrior(p: Double): Unit = priorValue = p

  @inline def q: Double =
    val n = visits.get()
    if n == 0 then 0.0 else java.lang.Double.longBitsToDouble(valueSum.get()) / n.toDouble

  def addValue(delta: Double): Unit =
    var updated = false
    while !updated do
      val cur = valueSum.get()
      val curD = java.lang.Double.longBitsToDouble(cur)
      val nxt = java.lang.Double.doubleToRawLongBits(curD + delta)
      updated = valueSum.compareAndSet(cur, nxt)

final class MctsNode(val state: GameState, val key: TranspositionKey):
  val visits = AtomicInteger(0)
  val expanded = AtomicBoolean(false)
  val terminal = AtomicBoolean(false)

  @volatile private var childrenArr: Array[(Move, MctsChild)] = Array.empty
  @volatile private var childIndex: Map[Move, MctsChild] = Map.empty

  def setChildren(entries: Array[(Move, MctsChild)]): Unit =
    childrenArr = entries
    childIndex = entries.iterator.map((m, c) => m -> c).toMap

  def childrenArray: Array[(Move, MctsChild)] = childrenArr
  def childForMove(move: Move): Option[MctsChild] = childIndex.get(move)

final class MctsChild(val edge: MctsEdge, val node: MctsNode)

final case class TranspositionKey(zobrist: Long)

final case class TranspositionEntry(
  key: TranspositionKey,
  visits: Int,
  value: Double,
  bestMove: Option[Move],
  depthHint: Int
)

trait TranspositionTable:
  def get(key: TranspositionKey): Option[TranspositionEntry]
  def put(entry: TranspositionEntry): Unit
  def clear(): Unit

class InMemoryTranspositionTable(maxSize: Int = 2_000_000) extends TranspositionTable:
  private val table = java.util.concurrent.ConcurrentHashMap[Long, TranspositionEntry]()

  override def get(key: TranspositionKey): Option[TranspositionEntry] = Option(table.get(key.zobrist))

  override def put(entry: TranspositionEntry): Unit =
    if table.size() < maxSize then table.put(entry.key.zobrist, entry)

  override def clear(): Unit = table.clear()

object Zobrist:
  private val pieceRand = Array.ofDim[Long](2, 6, 64)
  private val castlingRand = Array.ofDim[Long](4)
  private val enPassantFileRand = Array.ofDim[Long](8)
  private val sideRand: Long = splitMix64(0x9e3779b97f4a7c15L)

  init()

  def key(state: GameState): TranspositionKey =
    var h = 0L
    state.board.pieces.foreach { case (pos, piece) =>
      h ^= pieceValue(piece.color, piece.pieceType, pos.row * 8 + pos.col)
    }
    h = applyMeta(h, state)
    TranspositionKey(h)

  // Incremental update using changed squares + metadata toggles.
  def nextKey(prevState: GameState, prevKey: TranspositionKey, nextState: GameState): TranspositionKey =
    var h = prevKey.zobrist

    // remove previous meta
    h = unapplyMeta(h, prevState)

    // xor out changed old pieces
    prevState.board.pieces.foreach { case (pos, piece) =>
      nextState.board.get(pos) match
        case Some(same) if same == piece => ()
        case _ => h ^= pieceValue(piece.color, piece.pieceType, pos.row * 8 + pos.col)
    }

    // xor in changed/new pieces
    nextState.board.pieces.foreach { case (pos, piece) =>
      prevState.board.get(pos) match
        case Some(same) if same == piece => ()
        case _ => h ^= pieceValue(piece.color, piece.pieceType, pos.row * 8 + pos.col)
    }

    // apply next meta
    h = applyMeta(h, nextState)
    TranspositionKey(h)

  private def pieceValue(color: Color, pt: PieceType, sq: Int): Long =
    val c = if color == Color.White then 0 else 1
    pieceRand(c)(pt.ordinal)(sq)

  private def applyMeta(h0: Long, state: GameState): Long =
    var h = h0
    if state.castlingRights.whiteKingSide then h ^= castlingRand(0)
    if state.castlingRights.whiteQueenSide then h ^= castlingRand(1)
    if state.castlingRights.blackKingSide then h ^= castlingRand(2)
    if state.castlingRights.blackQueenSide then h ^= castlingRand(3)
    state.enPassantTarget.foreach(ep => h ^= enPassantFileRand(ep.col))
    if state.activeColor == Color.Black then h ^= sideRand
    h

  private def unapplyMeta(h0: Long, state: GameState): Long = applyMeta(h0, state)

  private def init(): Unit =
    var seed = 0x243f6a8885a308d3L
    var c = 0
    while c < 2 do
      var p = 0
      while p < 6 do
        var s = 0
        while s < 64 do
          seed = splitMix64(seed)
          pieceRand(c)(p)(s) = seed
          s += 1
        p += 1
      c += 1

    var i = 0
    while i < 4 do
      seed = splitMix64(seed)
      castlingRand(i) = seed
      i += 1

    i = 0
    while i < 8 do
      seed = splitMix64(seed)
      enPassantFileRand(i) = seed
      i += 1

  private def splitMix64(x: Long): Long =
    var z = x + 0x9e3779b97f4a7c15L
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
    z ^ (z >>> 31)

