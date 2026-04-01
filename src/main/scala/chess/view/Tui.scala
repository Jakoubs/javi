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
          GameController.eval(TuiCommandParser.parse(input.trim))
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
    app.message.foreach(console.print)

object TuiCommandParser:
  import chess.controller.Command

  def parse(input: String): Command =
    val trimmed = input.trim.toLowerCase

    trimmed match
      case "flip"               => Command.Flip
      case "undo"               => Command.Undo
      case "resign"             => Command.Resign
      case "draw"               => Command.OfferDraw
      case "new" | "newgame"    => Command.NewGame
      case "help" | "?"         => Command.Help
      case "quit" | "exit" | "q"=> Command.Quit
      case "ai"                 => Command.AiMove
      case "ai w" | "ai white"  => Command.ToggleAi(Color.White)
      case "ai b" | "ai black"  => Command.ToggleAi(Color.Black)
      case s if s.startsWith("train ") =>
        val nStr = s.drop(6).trim
        nStr.toIntOption match
          case Some(n) if n > 0 => Command.AiTrain(n)
          case _                => Command.Unknown(s"Invalid training count: $nStr")
      case s if s.startsWith("moves ") =>
        val posStr = s.drop(6).trim
        Pos.fromAlgebraic(posStr) match
          case Some(pos) => Command.SelectSquare(Some(pos))
          case None      => Command.Unknown(s"Invalid position: $posStr")
      case s if (s.length >= 4 && s.length <= 7) =>
        val normalized = s.replace("-", "")
        if (normalized.length == 4 || normalized.length == 5) then
          val fromPos = Pos.fromAlgebraic(normalized.take(2))
          val toPos   = Pos.fromAlgebraic(normalized.substring(2, 4))
          val promo   = normalized.drop(4)
          val validPromo = promo.isEmpty || (promo.length == 1 && "qrbn".contains(promo.head))
          
          if fromPos.isDefined && toPos.isDefined && validPromo then
            Command.ProcessTurn(s)
          else
            Command.Unknown(s"Invalid move format: $s")
        else
          Command.Unknown(s"Invalid move format: $s")
      case s =>
        Command.Unknown(s"Unknown command: $s")
