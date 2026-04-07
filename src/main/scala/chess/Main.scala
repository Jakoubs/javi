package chess

import chess.controller.GameController
import chess.view.{Tui, Gui}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object Main:
  def main(args: Array[String]): Unit =
    setup(args)
    // Start ScalaFX GUI (takes over the main thread)
    Gui.main(args)

  def setup(args: Array[String]): Unit =
    
    val tui = new Tui()
    GameController.addObserver(tui)
    
    // TUI blocks on readLine, so we push it to a background thread
    Future { tui.run() }
