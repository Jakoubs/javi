package chess.view

import chess.controller.{AppState, ConsoleIO, GameController, Command}
import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.Buffer

class TuiSpec extends AnyFunSpec with Matchers {

  class TestConsoleIO(inputs: List[String]) extends ConsoleIO {
    var inputQueue = inputs
    val outputBuffer = Buffer[String]()
    var clearedCount = 0

    override def readLine(): Option[String] = {
      inputQueue match {
        case head :: tail =>
          inputQueue = tail
          Some(head)
        case Nil => None
      }
    }

    override def print(text: String): Unit = {
      outputBuffer += text
    }

    override def clear(): Unit = {
      clearedCount += 1
    }
  }

  describe("Tui") {
    it("should process inputs and update state until quit") {
      // Setup mock IO with a move and then quit
      val io = new TestConsoleIO(List("e2e4", "quit"))
      val tui = new Tui(io)
      
      // Save original app state and reset for test
      val originalState = GameController.appState
      GameController.appState = AppState.initial
      
      GameController.addObserver(tui)
      try {
        tui.run()
        
        // Verify state changed
        GameController.appState.game.history.size should be >= 1
        GameController.appState.running shouldBe false
        
        // Verify output contains board elements
        val combinedOutput = io.outputBuffer.mkString
        combinedOutput should include ("White")
        combinedOutput should include ("Black")
      } finally {
        // Restore state
        GameController.removeObserver(tui)
        GameController.appState = originalState
      }
    }

    it("should request renders on update()") {
      val io = new TestConsoleIO(Nil)
      val tui = new Tui(io)
      
      val state = AppState.initial
      tui.update(state)
      
      io.clearedCount shouldBe 1
      val combinedOutput = io.outputBuffer.mkString
      combinedOutput should include ("White")
      combinedOutput should include ("Move 1")
    }

    it("should handle EOF as quit") {
      val io = new TestConsoleIO(Nil) // EOF immediately
      val tui = new Tui(io)
      
      val originalState = GameController.appState
      GameController.appState = AppState.initial
      
      try {
        tui.run()
        GameController.appState.running shouldBe false
      } finally {
        GameController.appState = originalState
      }
    }
  }
}
