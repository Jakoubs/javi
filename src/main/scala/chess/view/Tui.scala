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
    app.message.foreach(console.print)

object CommandParser:
  import chess.controller.Command

  def parse(input: String, app: AppState): Command =
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
      case s if s.startsWith("parser ") =>
        val parts = s.drop(7).trim.split("\\s+").filter(_.nonEmpty)
        if parts.length == 2 then 
          Command.SwitchParser(parts(0), parts(1))
        else 
          Command.Unknown("Usage: parser <type> <variant> (e.g. parser pgn fast)")
      case s if s.length >= 2 =>
        import chess.util.parser.ParserRegistry
        val parser = ParserRegistry.moveParsers.getOrElse(app.activeMoveParser, chess.util.parser.CoordinateMoveParser)
        parser.parse(s, app.game) match
          case scala.util.Success(move) => Command.ApplyMove(move)
          case scala.util.Failure(e)    => 
            // If it's a very short input and failed, maybe it's just garbage, but let's report it
            if s.length >= 4 || app.activeMoveParser == "san" then
              Command.Unknown(s"Invalid move format (${app.activeMoveParser}): ${e.getMessage}")
            else
              Command.Unknown(s"Unknown command: $s")
      case s =>
        Command.Unknown(s"Unknown command: $s")

