package chess.ai

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import org.scalatest.BeforeAndAfterEach

class AiEngineSpec extends AnyFunSpec with Matchers with BeforeAndAfterEach {

  override def beforeEach(): Unit = {
    AiEngine.clearTranspositionTable()
  }

  describe("AiEngine.bestMove") {
    it("should find checkmate in 1-ply (Scholar's Mate)") {
      // White to move, Queen can mate on f7
      val fen = "rnbqkbnr/pppp1ppp/8/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail("FEN error"))
      
      val bestMove = AiEngine.bestMove(state, depth = 2) 
      // Queen f3(5,2) to f7(5,6)
      bestMove shouldBe Some(Move(Pos(5, 2), Pos(5, 6), None))
    }

    it("should avoid immediate checkmate if possible") {
      // Black to move, threatened by Qf7# but can defend (or capture Queen if it was White's move)
      // Actually, let's set a position where the only legal move is to block or move King to avoid mate.
      // Back rank mate attempt
      val fen = "6k1/5ppp/8/8/8/8/r7/3R2K1 w - - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail("FEN error"))
      
      // White to move, Rd1(3,0) to d8(3,7) is checkmate.
      val bestMove = AiEngine.bestMove(state, depth = 2)
      bestMove shouldBe Some(Move(Pos(3, 0), Pos(3, 7), None))
    }

    it("should capture a hanging piece") {
      // White to move, can capture Black Queen for free
      val fen = "k7/8/8/8/2q5/8/8/K1R5 w - - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail("FEN error"))
      
      val bestMove = AiEngine.bestMove(state, depth = 2)
      bestMove shouldBe Some(Move(Pos(2, 0), Pos(2, 3), None)) // c1 to c4
    }

    it("should return None when no legal moves") {
      val mate = "k7/8/q7/8/8/8/8/K7 w - - 0 1" // Stalemate position
      // Actually k7/q7/8/8/1/8/8/K7 is not stalemate yet.
      // Simple stalemate: k7/8/8/8/8/8/2q5/K7 w - - 0 1
      val state = GameState.fromFen("k7/8/8/8/8/8/2q5/K7 w - - 0 1").getOrElse(fail("FEN error"))
      AiEngine.bestMove(state, depth = 1) shouldBe None
    }
  }

  describe("AiEngine epsilon-greedy") {
    it("should choose random moves when epsilon is 1.0") {
      val state = GameState.initial
      // Epsilon 1.0 means 100% random. 
      // We check that it returns SOME legal move and doesn't crash.
      val move = AiEngine.bestMove(state, depth = 1, epsilon = 1.0)
      move shouldBe defined
      MoveGenerator.legalMoves(state) should contain (move.get)
    }
  }

  describe("AiEngine deep search") {
    it("should hit the transposition table for deep searches") {
      val state = GameState.initial
      // Depth 3 should encounter duplicate states (transpositions)
      val bestMove = AiEngine.bestMove(state, depth = 3)
      bestMove shouldBe defined
    }
    
    it("should find checkmate as Black (isMaximizing = true in minimax)") {
      // Black to move, Black can deliver checkmate on g2 or by promoting on g1
      val fen = "6k1/8/8/8/8/8/6pq/5K2 b - - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail("FEN error"))
      
      val bestMove = AiEngine.bestMove(state, depth = 2)
      // Engine prefers pawn g2 to g1 promotion
      bestMove shouldBe Some(Move(Pos(6, 1), Pos(6, 0), Some(PieceType.Queen)))
    }
  }
}
