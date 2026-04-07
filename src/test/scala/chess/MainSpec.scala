package chess

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import chess.controller.GameController

class MainSpec extends AnyFunSpec with Matchers {
  describe("Main") {
    it("should setup without errors") {
      // Set playing to false to ensure TUI loop (if it starts) doesn't run forever
      GameController.eval(chess.controller.Command.Quit)
      
      noException should be thrownBy {
        Main.setup(Array.empty)
      }
    }
  }
}
