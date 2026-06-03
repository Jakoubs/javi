package chess.ai

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import java.io.File
import java.nio.file.Files

class EvaluatorSpec extends AnyFunSpec with Matchers {
  
  describe("Evaluator.evaluate") {
    it("should return 0.0 for an empty board") {
      val state = WhiteToMove(Board.empty, CastlingRights(), None, 0, 1)
      Evaluator.evaluate(state) shouldBe 0.0
    }

    it("should score material advantages correctly") {
      // White has an extra Queen
      val board = Board.empty
        .put(Pos(4,0), Piece(Color.White, PieceType.King))
        .put(Pos(4,7), Piece(Color.Black, PieceType.King))
        .put(Pos(0,0), Piece(Color.White, PieceType.Queen))
      
      val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
      Evaluator.evaluate(state) should be > 0.0
      
      // Black has an extra Rook
      val board2 = board.put(Pos(7,7), Piece(Color.Black, PieceType.Rook))
      val state2 = WhiteToMove(board2, CastlingRights(), None, 0, 1)
      Evaluator.evaluate(state2) should be < Evaluator.evaluate(state)
    }

    it("should include PST bonuses (Knight in center vs corner)") {
      val kingPos = Pos(4,0)
      val blackKingPos = Pos(4,7)
      
      // Knight on a1 (corner)
      val cornerBoard = Board.empty
        .put(kingPos, Piece(Color.White, PieceType.King))
        .put(blackKingPos, Piece(Color.Black, PieceType.King))
        .put(Pos(0,0), Piece(Color.White, PieceType.Knight))
      
      // Knight on d4 (center)
      val centerBoard = Board.empty
        .put(kingPos, Piece(Color.White, PieceType.King))
        .put(blackKingPos, Piece(Color.Black, PieceType.King))
        .put(Pos(3,3), Piece(Color.White, PieceType.Knight))
      
      val cornerEval = Evaluator.evaluate(WhiteToMove(cornerBoard, CastlingRights(), None, 0, 1))
      val centerEval = Evaluator.evaluate(WhiteToMove(centerBoard, CastlingRights(), None, 0, 1))
      
      centerEval should be > cornerEval
    }
    
    it("should apply opening heuristics (development bonus)") {
      val initial = GameState.initial
      val evalInitial = Evaluator.evaluate(initial)
      
      // Move a knight out
      val developed = GameRules.applyMove(initial, Move(Pos(1,0), Pos(2,2)))
      val evalDeveloped = Evaluator.evaluate(developed)
      
      evalDeveloped should be > evalInitial
    }

    it("should evaluate Center Pawns bonus") {
      // White has pawns on e4 and d4
      val fen = "rnbqkbnr/pppppppp/8/8/3PP3/8/PPP2PPP/RNBQKBNR w KQkq - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail())
      Evaluator.evaluate(state) should not equal 0.0
    }

    it("should evaluate Center Knights bonus") {
      // White has knights on c3 and f3
      val fen = "rnbqkbnr/pppppppp/8/8/8/2N2N2/PPPPPPPP/R1BQKB1R w KQkq - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail())
      Evaluator.evaluate(state) should not equal 0.0
    }

    it("should penalize Early Queen with undeveloped minors") {
      // White queen on f3, minors at home
      val fen = "rnbqkbnr/pppppppp/8/8/8/5Q2/PPPPPPPP/RNB1KBNR w KQkq - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail())
      Evaluator.evaluate(state) should not equal 0.0
    }

    it("should reward Connected Rooks") {
      // White rooks on a1, h1 are connected (no pieces between b1-g1)
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/R6R w KQkq - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail())
      Evaluator.evaluate(state) should not equal 0.0
    }

    it("should penalize missing f and h pawns") {
      // White f2 and h2 pawns are missing
      val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPP1P1P/RNBQKBNR w KQkq - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail())
      Evaluator.evaluate(state) should not equal 0.0
    }
  }

  describe("Evaluator persistence") {
    it("should save and load weights from a temporary file") {
      val tempFile = Files.createTempFile("ai_weights_test", ".txt").toFile
      tempFile.deleteOnExit()
      
      try {
        // 1. Initial save
        Evaluator.saveWeights(tempFile.getAbsolutePath)
        val sizeBefore = tempFile.length()
        sizeBefore should be > 0L
        
        // 2. Modify a weight
        Evaluator.updateWeights(10.0, PieceType.Pawn, Color.White, None)
        Evaluator.saveWeights(tempFile.getAbsolutePath)
        
        // 3. Load and check (we can't easily check internal state, but we check if it doesn't crash)
        Evaluator.loadWeights(tempFile.getAbsolutePath)
      } finally {
        tempFile.delete()
      }
    }

    it("should use default save/load paths and expose weight accessors") {
      val weightsFile = new File("ai_weights.txt")

      try {
        Evaluator.resetWeights()
        val savedPenalty = Evaluator.getOpeningPawnPushPenalty.get()
        Evaluator.updateWeights(7.0, PieceType.Pawn, Color.White)
        Evaluator.saveWeights()
        weightsFile.exists() shouldBe true

        Evaluator.updateGlobalWeight(Evaluator.getOpeningPawnPushPenalty, 3.0)
        Evaluator.pawnWeight_test_access.get() shouldBe 107.0
        Evaluator.getOpeningPawnPushPenalty.get() should not be savedPenalty

        Evaluator.loadWeights()
        Evaluator.pawnWeight_test_access.get() shouldBe 107.0
        Evaluator.getOpeningPawnPushPenalty.get() shouldBe savedPenalty
      } finally {
        Evaluator.resetWeights()
        weightsFile.delete()
      }
    }
  }
  
  describe("Evaluator weight updates") {
    it("should update global weights") {
      val ref = Evaluator.getOpeningCenterPawn
      val initial = ref.get()
      Evaluator.updateGlobalWeight(ref, 5.0)
      ref.get() shouldBe (initial + 5.0)
    }
  }

  describe("Evaluator Pawn Structure") {
    it("should penalize doubled pawns") {
      Evaluator.resetWeights()
      // Healthy: two pawns on separate files (a2, b2)
      val fenHealthy = "4k3/8/8/8/8/8/PP6/4K3 w - - 0 1"
      val stateHealthy = GameState.fromFen(fenHealthy).getOrElse(fail())
      
      // Doubled: two pawns on the same file (a2, a3)
      val fenDoubled = "4k3/8/8/8/8/P7/P7/4K3 w - - 0 1"
      val stateDoubled = GameState.fromFen(fenDoubled).getOrElse(fail())
      
      val scoreHealthy = Evaluator.evaluate(stateHealthy)
      val scoreDoubled = Evaluator.evaluate(stateDoubled)
      
      scoreDoubled should be < scoreHealthy
    }

    it("should penalize isolated pawns") {
      Evaluator.resetWeights()
      // Connected: pawns on b2, c2
      val fenConnected = "4k3/8/8/8/8/8/1PP5/4K3 w - - 0 1"
      val stateConnected = GameState.fromFen(fenConnected).getOrElse(fail())
      
      // Isolated: pawns on a2, h2
      val fenIsolated = "4k3/8/8/8/8/8/P6P/4K3 w - - 0 1"
      val stateIsolated = GameState.fromFen(fenIsolated).getOrElse(fail())
      
      val scoreConnected = Evaluator.evaluate(stateConnected)
      val scoreIsolated = Evaluator.evaluate(stateIsolated)
      
      scoreIsolated should be < scoreConnected
    }

    it("should reward passed pawns based on advancement") {
      Evaluator.resetWeights()
      val fenA2 = "4k3/8/8/8/8/8/P7/4K3 w - - 0 1"
      val stateA2 = GameState.fromFen(fenA2).getOrElse(fail())
      
      val fenA6 = "4k3/8/P7/8/8/8/8/4K3 w - - 0 1"
      val stateA6 = GameState.fromFen(fenA6).getOrElse(fail())
      
      val scoreA2 = Evaluator.evaluate(stateA2)
      val scoreA6 = Evaluator.evaluate(stateA6)
      
      scoreA6 should be > scoreA2
    }
  }

  describe("Evaluator Endgame Logic") {
    it("should encourage King to move towards center in endgame") {
      Evaluator.resetWeights()
      // White King on g1 (corner/edge), Black King on e8
      val fenCorner = "4k3/p7/8/8/8/8/P7/6K1 w - - 0 1"
      val stateCorner = GameState.fromFen(fenCorner).getOrElse(fail())
      
      // White King on d4 (center)
      val fenCenter = "4k3/p7/8/8/3K4/8/P7/8 w - - 0 1"
      val stateCenter = GameState.fromFen(fenCenter).getOrElse(fail())
      
      val scoreCorner = Evaluator.evaluate(stateCorner)
      val scoreCenter = Evaluator.evaluate(stateCenter)
      
      scoreCenter should be > scoreCorner
    }

    it("should encourage King to stay back in opening/middlegame") {
      Evaluator.resetWeights()
      val fenSafe = "r1bqk2r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPPBPPP/R1BQK2R w KQkq - 0 1"
      val stateSafe = GameState.fromFen(fenSafe).getOrElse(fail())
      
      val fenExposed = "r1bqk2r/pppp1ppp/2n2n2/4K3/4P3/2N2N2/PPPPBPPP/R1BQ3R w kq - 0 1"
      val stateExposed = GameState.fromFen(fenExposed).getOrElse(fail())
      
      val scoreSafe = Evaluator.evaluate(stateSafe)
      val scoreExposed = Evaluator.evaluate(stateExposed)
      
      scoreSafe should be > scoreExposed
    }
  }
}
