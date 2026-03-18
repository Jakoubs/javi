package chess.controller

import chess.model.*

import java.util.concurrent.CopyOnWriteArrayList

/** Shared game state for multiple UIs (TUI + GUI). */
final case class CoreState(
  game:      GameState,
  status:    GameStatus,
  lastMove:  Option[Move] = None,
  drawOffer: Boolean      = false
)

object CoreState:
  def initial: CoreState =
    val g = GameState.initial
    CoreState(game = g, status = GameRules.computeStatus(g))

final class GameSession(initial: CoreState = CoreState.initial):

  private val listeners = new CopyOnWriteArrayList[CoreState => Unit]()

  @volatile private var core: CoreState = initial

  def snapshot(): CoreState = core

  def addListener(listener: CoreState => Unit): Unit =
    listeners.add(listener)

  /** Apply a core command and notify listeners. Returns an optional plain message for the UI. */
  def dispatch(cmd: Command): Option[String] =
    val (next, msg) = CoreReducer.apply(core, cmd)
    if next != core then
      core = next
      val it = listeners.iterator()
      while it.hasNext do it.next().apply(core)
    msg

/** Core-only reducer (no UI-specific state like highlights/flip/help text). */
object CoreReducer:

  def apply(core: CoreState, cmd: Command): (CoreState, Option[String]) =
    import Command.*
    cmd match
      case MakeMove(move) => handleMove(core, move)
      case Undo           => handleUndo(core)
      case Resign         => handleResign(core)
      case OfferDraw      => handleDraw(core)
      case NewGame        => (CoreState.initial, Some("New game started."))
      case Quit           => (core, Some("Quit."))

      // UI-specific commands are ignored at core level
      case Flip | ShowMoves(_) | Help => (core, None)
      case Unknown(msg)              => (core, Some(msg))

  private def handleMove(core: CoreState, move: Move): (CoreState, Option[String]) =
    core.status match
      case GameStatus.Checkmate(_) | GameStatus.Stalemate | GameStatus.Draw(_) =>
        return (core, Some("Game is over. Type 'new' to start again."))
      case _ => ()

    val legal = MoveGenerator.legalMoves(core.game)
    val isLegal = legal.exists(m => m.from == move.from && m.to == move.to && m.promotion == move.promotion)
    if !isLegal then
      val promotionRequired =
        legal.exists(m => m.from == move.from && m.to == move.to && m.promotion.isDefined) && move.promotion.isEmpty
      if promotionRequired then
        return (core, Some("Pawn promotion required. Append q/r/b/n (e.g. e7e8q)."))
      else
        return (core, Some("Illegal move."))

    val newGame   = GameRules.applyMove(core.game, move)
    val newStatus = GameRules.computeStatus(newGame)

    val msg = newStatus match
      case GameStatus.Checkmate(loser) =>
        val winner = if loser == Color.White then "Black" else "White"
        Some(s"Checkmate! $winner wins!")
      case GameStatus.Stalemate    => Some("Stalemate — draw!")
      case GameStatus.Draw(reason) => Some(s"Draw by $reason.")
      case GameStatus.Check(_)     => Some("Check!")
      case GameStatus.Playing      => None

    (
      core.copy(
        game      = newGame,
        status    = newStatus,
        lastMove  = Some(move),
        drawOffer = false
      ),
      msg
    )

  private def handleUndo(core: CoreState): (CoreState, Option[String]) =
    core.game.history.lastOption match
      case None => (core, Some("Nothing to undo."))
      case Some(prev) =>
        val st = GameRules.computeStatus(prev)
        (core.copy(game = prev, status = st, lastMove = None), Some("Last move undone."))

  private def handleResign(core: CoreState): (CoreState, Option[String]) =
    val loser  = core.game.activeColor
    val winner = if loser == Color.White then "Black" else "White"
    (core.copy(status = GameStatus.Checkmate(loser)), Some(s"$loser resigns. $winner wins!"))

  private def handleDraw(core: CoreState): (CoreState, Option[String]) =
    if core.drawOffer then
      (core.copy(status = GameStatus.Draw("agreement"), drawOffer = false), Some("Draw accepted."))
    else
      (core.copy(drawOffer = true), Some(s"${core.game.activeColor} offers a draw. Type 'draw' to accept."))

