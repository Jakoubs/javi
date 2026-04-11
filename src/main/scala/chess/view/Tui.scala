package chess.view

import chess.controller.{AppState, ConsoleIO, GameController, StdConsoleIO, Command}
import chess.util.Observer
import chess.model.{GameStatus, Color, Pos}

class Tui(console: ConsoleIO = StdConsoleIO) extends Observer[AppState]:

  override def update(app: AppState): Unit =
    renderFull(app, console)

  def run(): Unit =
    chess.ai.Evaluator.loadWeights()
    // Trigger initial render
    GameController.notifyObservers(GameController.appState)

    while GameController.appState.running do
      console.print(TerminalView.prompt)
      console.readLine() match
        case Some(input) =>
          GameController.eval(CommandParser.parse(input.trim, GameController.appState))
        case None =>
          // Handle EOF/quit
          GameController.eval(chess.controller.Command.Quit)

  private def renderFull(app: AppState, console: ConsoleIO): Unit =
    console.clear()
    console.print(TerminalView.render(
      state      = app.game,
      status     = app.status,
      highlights = app.highlights,
      lastMove   = app.lastMove,
      flipped    = app.flipped
    ))
    if app.training then
      console.print(TerminalView.info("AI is currently training in the background..."))
    if app.aiWhite then
      console.print(TerminalView.info("AI playing for White | Type 'ai white' to disable"))
    if app.aiBlack then
      console.print(TerminalView.info("AI playing for Black | Type 'ai black' to disable"))
    app.trainingProgress.foreach(msg => console.print(TerminalView.info(msg)))
    app.message.foreach(console.print)

