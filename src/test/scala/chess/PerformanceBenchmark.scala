package chess

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import chess.model.*
import chess.controller.*
import chess.view.{TerminalView, CommandParser}

class PerformanceBenchmark extends AnyFunSuite with Matchers:

  test("Functional GameController performance") {
    val iterations = 1000
    
    val start = System.nanoTime()
    
    // Test functional operations
    var app = AppState.initial
    for (i <- 1 to iterations) {
      app = GameController.handleCommand(app, Command.Flip)
      app = GameController.handleCommand(app, Command.Flip) // Flip back
    }
    
    val end = System.nanoTime()
    val duration = (end - start) / 1000000.0 // Convert to milliseconds
    
    println(s"✅ Functional operations: $iterations iterations in ${duration}ms")
    println(s"📊 Average per operation: ${duration/(iterations*2)}ms")
    
    // Should be reasonably fast (less than 1 second for 2000 operations)
    duration should be < 1000.0
  }

  test("TerminalView rendering performance") {
    val state = GameState.initial
    val status = GameStatus.Playing
    val iterations = 100
    
    val start = System.nanoTime()
    
    // Test functional rendering
    val results = (1 to iterations).map { _ =>
      TerminalView.render(state, status)
    }
    
    val end = System.nanoTime()
    val duration = (end - start) / 1000000.0
    
    println(s"✅ TerminalView rendering: $iterations renders in ${duration}ms")
    println(s"📊 Average per render: ${duration/iterations}ms")
    
    // All renders should be identical (functional purity)
    results.foreach(_ shouldBe results.head)
    
    // Should be reasonably fast
    duration should be < 500.0
  }

  test("Command parsing performance") {
    val commands = List("e2e4", "e7e5", "flip", "undo", "new", "quit")
    val iterations = 1000
    
    val start = System.nanoTime()
    
    // Test functional parsing
    val results = (1 to iterations).flatMap { _ =>
      commands.map(CommandParser.parse)
    }
    
    val end = System.nanoTime()
    val duration = (end - start) / 1000000.0
    
    println(s"✅ Command parsing: ${iterations * commands.length} parses in ${duration}ms")
    println(s"📊 Average per parse: ${duration/(iterations * commands.length)}ms")
    
    // Should be very fast
    duration should be < 100.0
  }

  test("Move generation performance") {
    val game = GameState.initial
    val iterations = 100
    
    val start = System.nanoTime()
    
    // Test functional move generation
    val results = (1 to iterations).map { _ =>
      MoveGenerator.legalMoves(game)
    }
    
    val end = System.nanoTime()
    val duration = (end - start) / 1000000.0
    
    println(s"✅ Move generation: $iterations calculations in ${duration}ms")
    println(s"📊 Average per calculation: ${duration/iterations}ms")
    
    // All results should be identical (functional purity)
    results.foreach(_ shouldBe results.head)
    
    // Should be reasonably fast
    duration should be < 200.0
  }
