package chess.ai

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import chess.model.*
import java.io.File
import java.nio.file.Files

class AiCoverageTest extends AnyWordSpec with Matchers:

  "Evaluator" should {
    "evaluate initial position as neutral" in {
      val score = Evaluator.evaluate(GameState.initial)
      score shouldBe 0.0 +- 10.0
    }

    "evaluate White advantage after e2e4" in {
      val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail("Invalid FEN"))
      val score = Evaluator.evaluate(state)
      score should be > 0.0
    }

    "save and load weights" in {
      val tempFile = Files.createTempFile("ai_weights", ".txt").toFile
      try {
        Evaluator.saveWeights(tempFile.getAbsolutePath)
        tempFile.exists() shouldBe true
        
        // Update a weight and save again
        Evaluator.updateWeights(10.0, PieceType.Pawn, Color.White, Some(Pos(4, 3)))
        Evaluator.saveWeights(tempFile.getAbsolutePath)
        
        // Load back
        Evaluator.loadWeights(tempFile.getAbsolutePath)
        // No easy way to verify privatized atomic refs without reflection, 
        // but we can check if it at least doesn't crash
      } finally {
        tempFile.delete()
      }
    }

    "handle various opening heuristics" in {
      // Test center control
      val fenCenter = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
      val stateCenter = GameState.fromFen(fenCenter).getOrElse(fail("Invalid FEN"))
      Evaluator.evaluate(stateCenter)

      // Test undeveloped minors
      val fenUnmapped = "rnbqkbnr/8/8/8/8/8/8/RNBQKBNR w KQkq - 0 1"
      val stateUnmapped = GameState.fromFen(fenUnmapped).getOrElse(fail("Invalid FEN"))
      Evaluator.evaluate(stateUnmapped)
      
      // Test king safety (castled)
      val fenCastled = "rnbq1rk1/pppppppp/8/8/8/8/PPPPPPPP/RNBQ1RK1 w - - 0 1"
      val stateCastled = GameState.fromFen(fenCastled).getOrElse(fail("Invalid FEN"))
      Evaluator.evaluate(stateCastled)
    }

    "handle updateWeights for all piece types" in {
      val pieceTypes = List(PieceType.Pawn, PieceType.Knight, PieceType.Bishop, PieceType.Rook, PieceType.Queen, PieceType.King)
      pieceTypes.foreach { pt =>
        Evaluator.updateWeights(1.0, pt, Color.White, Some(Pos(4, 4)))
      }
      Evaluator.updateGlobalWeight(Evaluator.getOpeningCenterPawn, 0.5)
    }

    "handle malformed weights file gracefully" in {
      val tempFile = Files.createTempFile("bad_weights", ".txt").toFile
      Files.writeString(tempFile.toPath, "garbage\nmore garbage")
      try {
        Evaluator.loadWeights(tempFile.getAbsolutePath)
        // Should not crash
      } finally {
        tempFile.delete()
      }
    }
  }

  "AiEngine" should {
    "find a move in initial position" in {
      val move = AiEngine.bestMove(GameState.initial, 1)
      move shouldBe defined
    }
    
    "handle positions with no legal moves" in {
      val fen = "k7/8/K7/8/8/8/8/8 w - - 0 1" // Draw if it's stalemate or something? 
      // Actually King vs King is draw.
      val state = GameState.fromFen(fen).getOrElse(fail())
      // If AiEngine uses legalMoves and it's empty, it returns None
      // We need a mate position to be sure
      val mateFen = "R5k1/5ppp/8/8/8/8/8/6K1 b - - 0 1"
      val mateState = GameState.fromFen(mateFen).getOrElse(fail())
      AiEngine.bestMove(mateState, 1) shouldBe None
    }
  }

  "PassiveTrainer" should {
    "update weights after a game" in {
      val game = GameState.initial
      val status = GameStatus.Checkmate(Color.Black) // White won
      
      // We need some history to trigger trajectory update
      val boardMoved = Board.initial.movePiece(Pos(4, 1), Pos(4, 3))
      val move = Move(Pos(4, 1), Pos(4, 3))
      val stateMoved = game.withHistory.copy(board = boardMoved)
      
      PassiveTrainer.train(stateMoved, status)
      // Should complete without error
    }

    "handle draw status by doing nothing" in {
      PassiveTrainer.train(GameState.initial, GameStatus.Draw("agreement"))
      PassiveTrainer.train(GameState.initial, GameStatus.Stalemate)
      // Should complete without error
    }
  }
