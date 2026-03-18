package chess

import chess.controller.GameController

object Main:
  def main(args: Array[String]): Unit =
    println("Starting Chess...")
    GameController.run()
