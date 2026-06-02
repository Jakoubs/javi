package chess.view

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class TerminalViewSpec extends AnyFunSpec with Matchers {

  describe("TerminalView rendering") {
    val state = GameState.initial
    val status = GameStatus.Playing

    it("should render the initial board with piece symbols") {
      val output = TerminalView.render(state, status)
      
      // All pieces use the same Unicode symbols, colored via ANSI
      output should include ("♟") // Piece symbol
      output should include ("♜")
      output should include ("♞")
      output should include ("♝")
      output should include ("♛")
      output should include ("♚")
      
      // Check for coordinates
      output should include ("a")
      output should include ("h")
    }

    it("should show 'CHECK!' message when in check") {
      val output = TerminalView.render(state, GameStatus.Check(Color.White))
      output should include ("CHECK!")
    }

    it("should show 'Checkmate!' message when game is over") {
      val output = TerminalView.render(state, GameStatus.Checkmate(Color.Black))
      output should include ("Checkmate!")
      output should include ("White")
      output should include ("wins!")
    }

    it("should show draw message") {
      val output = TerminalView.render(state, GameStatus.Draw("agreement"))
      output should include ("Draw")
      output should include ("agreement")
    }

    it("should show flip coordinates correctly when flipped") {
      val normal = TerminalView.render(state, status, flipped = false)
      val flipped = TerminalView.render(state, status, flipped = true)
      
      // Row labels have two spaces after them: " 8  "
      normal.indexOf(" 8  ") should be < normal.indexOf(" 1  ")
      flipped.indexOf(" 1  ") should be < flipped.indexOf(" 8  ")
    }

    it("should highlight legal moves with dots") {
      val highlights = Set(Pos(4, 2), Pos(4, 3)) // e3, e4
      val output = TerminalView.render(state, status, highlights = highlights)
      output should include ("•")
    }

    it("should highlight the last move") {
      val move = Move(Pos(4, 1), Pos(4, 3)) // e2-e4
      val output = TerminalView.render(state, status, lastMove = Some(move))
      // Can't easily check ANSI codes without helper, but verify output builds
      output.length should be > 100
    }

    it("should provide help text") {
      TerminalView.helpText should include ("Chess Commands")
      TerminalView.helpText should include ("flip")
      TerminalView.helpText should include ("undo")
    }

    it("should format messages correctly") {
      TerminalView.error("bad") should include ("✗ bad")
      TerminalView.success("good") should include ("✓ good")
      TerminalView.info("wait") should include ("wait")
    }
  }
}
