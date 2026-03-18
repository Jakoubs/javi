package chess

import chess.view.GuiApp
import chess.controller.GameSession

object GuiMain:
  def main(args: Array[String]): Unit =
    val session = new GameSession()
    GuiApp.start(session)
    // keep JVM alive while window is open
    while true do Thread.sleep(1000)

