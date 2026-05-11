package chess.ai.nn

import chess.model.{Move, PieceType}

trait MoveIndexer:
  def policySize: Int
  def indexOf(move: Move): Int

object MoveIndexer:
  // AlphaZero-style policy head size for chess: 64 * 73 = 4672
  val DefaultPolicySize: Int = 4672

class FastMoveIndexer(override val policySize: Int = MoveIndexer.DefaultPolicySize) extends MoveIndexer:
  override def indexOf(move: Move): Int =
    val from = move.from.row * 8 + move.from.col
    val dc = move.to.col - move.from.col
    val dr = move.to.row - move.from.row

    val plane =
      promotionPlane(dc, dr, move.promotion)
        .orElse(slidingPlane(dc, dr))
        .orElse(knightPlane(dc, dr))
        .getOrElse(0)

    from * 73 + plane

  private def promotionPlane(dc: Int, dr: Int, promo: Option[PieceType]): Option[Int] =
    promo.flatMap { p =>
      val dir = if dc < 0 then 0 else if dc == 0 then 1 else 2
      val promoOffset = p match
        case PieceType.Knight => 0
        case PieceType.Bishop => 3
        case PieceType.Rook => 6
        case _ => -1
      if promoOffset < 0 then None else Some(64 + promoOffset + dir)
    }

  private def knightPlane(dc: Int, dr: Int): Option[Int] =
    val knight = Map(
      (1, 2) -> 56,
      (2, 1) -> 57,
      (2, -1) -> 58,
      (1, -2) -> 59,
      (-1, -2) -> 60,
      (-2, -1) -> 61,
      (-2, 1) -> 62,
      (-1, 2) -> 63
    )
    knight.get((dc, dr))

  private def slidingPlane(dc: Int, dr: Int): Option[Int] =
    val stepC = Integer.signum(dc)
    val stepR = Integer.signum(dr)
    val dist = math.max(math.abs(dc), math.abs(dr))
    if dist < 1 || dist > 7 then None
    else
      val dirBase = (stepC, stepR) match
        case (0, 1) => 0
        case (0, -1) => 7
        case (1, 0) => 14
        case (-1, 0) => 21
        case (1, 1) => 28
        case (-1, 1) => 35
        case (1, -1) => 42
        case (-1, -1) => 49
        case _ => -1
      if dirBase < 0 then None else Some(dirBase + (dist - 1))

