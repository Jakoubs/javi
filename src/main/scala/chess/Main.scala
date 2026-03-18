package chess

import chess.controller.GameSession
import chess.view.{GuiApp, TuiRunner}

object Main:
  def main(args: Array[String]): Unit =
    println("Starting Chess (GUI + TUI)...")
    val session = new GameSession()

    // Start GUI
    GuiApp.start(session)

    // Run TUI loop (blocking) on main thread
    TuiRunner.run(session)
