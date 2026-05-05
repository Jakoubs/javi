package chess.ai2.core

import chess.ai2.book.OpeningBook
import chess.ai2.mcts.{MctsConfig, MctsSearch, TranspositionTable}
import chess.ai2.nn.PolicyValueNet
import chess.ai2.tablebase.EndgameOracle
import chess.model.GameState

class Ai2Engine(
  openingBook: OpeningBook,
  endgameOracle: EndgameOracle,
  net: PolicyValueNet,
  tt: TranspositionTable,
  mctsConfig: MctsConfig = MctsConfig()
) extends SearchEngine:

  private val mcts = new MctsSearch(net, tt, mctsConfig)

  override def findBestMove(state: GameState, limits: SearchLimits, ctx: SearchContext): SearchResult =
    openingBook.pick(state) match
      case Some(hit) =>
        SearchResult(
          bestMove = Some(hit.move),
          principalVariation = List(hit.move),
          scoreCp = 0,
          nodes = 0,
          depth = 0,
          fromBook = true
        )
      case None =>
        endgameOracle.probe(state) match
          case Some(tb) =>
            SearchResult(
              bestMove = Some(tb.bestMove),
              principalVariation = List(tb.bestMove),
              scoreCp = tb.wdl * 100,
              nodes = 0,
              depth = 0,
              fromTablebase = true
            )
          case None =>
            mcts.search(state, limits, ctx)

  def startPonder(state: GameState, ctx: SearchContext): Unit =
    mcts.startPonder(state, ctx)

  def stopPonder(): Unit =
    mcts.stopPonder()

