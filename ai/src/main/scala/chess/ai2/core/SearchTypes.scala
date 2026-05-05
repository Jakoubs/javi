package chess.ai2.core

import chess.model.{GameState, Move}

final case class SearchLimits(
  maxNodes: Int = 200000,
  softTimeMs: Long = 1000L,
  hardTimeMs: Long = 5000L,
  ponder: Boolean = false
)

final case class SearchContext(
  isTraining: Boolean,
  moveNumber: Int,
  lastOpponentMove: Option[Move] = None
)

final case class SearchResult(
  bestMove: Option[Move],
  principalVariation: List[Move],
  scoreCp: Int,
  nodes: Long,
  depth: Int,
  elapsedMs: Long = 0L,
  nps: Long = 0L,
  ttHits: Long = 0L,
  fromBook: Boolean = false,
  fromTablebase: Boolean = false
)

trait SearchEngine:
  def findBestMove(state: GameState, limits: SearchLimits, ctx: SearchContext): SearchResult

