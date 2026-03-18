package chess.view

import chess.controller.{Command, CommandParser, CoreState, GameSession}
import chess.model.*

/** Terminal UI that can share a GameSession with other UIs. */
object TuiRunner:

  private val outLock = new Object

  def run(session: GameSession): Unit =
    var flipped: Boolean = false
    var highlights: Set[Pos] = Set.empty
    var message: Option[String] = None

    def render(core: CoreState): Unit =
      outLock.synchronized {
        TerminalView.clear()
        print(
          TerminalView.render(
            state = core.game,
            status = core.status,
            highlights = highlights,
            lastMove = core.lastMove,
            flipped = flipped
          )
        )
        message.foreach(m => print(TerminalView.info(m)))
        message = None
        print(TerminalView.prompt)
      }

    // Re-render when GUI changes the shared session.
    session.addListener(core => render(core))

    render(session.snapshot())

    var running = true
    while running do
      val input = scala.io.StdIn.readLine()
      if input == null then running = false
      else
        CommandParser.parse(input.trim) match
          case Command.Flip =>
            flipped = !flipped
            highlights = Set.empty
            render(session.snapshot())
          case Command.Help =>
            outLock.synchronized {
              println()
              print(TerminalView.helpText)
              print(TerminalView.prompt)
            }
          case Command.ShowMoves(pos) =>
            val core = session.snapshot()
            core.game.board.get(pos) match
              case None =>
                highlights = Set.empty
                message = Some(s"No piece on ${pos.toAlgebraic}.")
              case Some(piece) if piece.color != core.game.activeColor =>
                highlights = Set.empty
                message = Some("That is not your piece.")
              case Some(_) =>
                val targets = MoveGenerator.legalMovesFrom(core.game, pos).map(_.to).toSet
                highlights = targets
                if targets.isEmpty then message = Some("No legal moves for that piece.")
            render(core)
          case Command.Quit =>
            running = false
          case cmd =>
            highlights = Set.empty
            message = session.dispatch(cmd)
            // render happens via listener; for commands that don't change state (illegal move) we render here
            if message.isDefined then render(session.snapshot())

