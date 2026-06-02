package chess.view

import chess.controller.{AppState, GameController, Command, MessageType, CommandParser}
import chess.util.Observer
import chess.model.{GameStatus, Color, Pos}

trait ConsoleIO:
  def readLine(): Option[String]
  def print(text: String): Unit
  def clear(): Unit

object StdConsoleIO extends ConsoleIO:
  def readLine(): Option[String] = Option(scala.io.StdIn.readLine())
  def print(text: String): Unit    = scala.Predef.print(text)
  def clear(): Unit                = TerminalView.clear()

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

    ()

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
    
    app.message.foreach { msg =>
      if msg == "HELP" then
        console.print(TerminalView.helpText)
      else
        app.messageType match
          case MessageType.Error   => console.print(TerminalView.error(msg))
          case MessageType.Success => console.print(TerminalView.success(msg))
          case MessageType.Info    => console.print(TerminalView.info(msg))
    }
