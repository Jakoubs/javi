package chess.ai2.time

import chess.ai2.core.SearchContext
import chess.model.{Color, GameState}

final case class ClockInfo(
  whiteMs: Long,
  blackMs: Long,
  whiteIncMs: Long,
  blackIncMs: Long
)

trait TimeManager:
  def allocateMs(state: GameState, clock: ClockInfo, ctx: SearchContext): Long

final case class DynamicTimeConfig(
  movesToGo: Int = 30,
  incrementWeight: Double = 0.75,
  extraTimeGainFactor: Double = 0.6,
  minThinkMs: Long = 50L,
  maxThinkFraction: Double = 0.25
)

class DynamicTimeManager(config: DynamicTimeConfig = DynamicTimeConfig()) extends TimeManager:
  override def allocateMs(state: GameState, clock: ClockInfo, ctx: SearchContext): Long =
    val (remaining, increment) = state.activeColor match
      case Color.White => (clock.whiteMs, clock.whiteIncMs)
      case Color.Black => (clock.blackMs, clock.blackIncMs)

    val base = (remaining.toDouble / config.movesToGo.toDouble) + (increment.toDouble * config.incrementWeight)
    val uncertainty = if ctx.isTraining then 0.25 else 0.15
    val withGain = base * (1.0 + config.extraTimeGainFactor * uncertainty)

    val cap = remaining.toDouble * config.maxThinkFraction
    math.max(config.minThinkMs.toDouble, math.min(withGain, cap)).toLong

