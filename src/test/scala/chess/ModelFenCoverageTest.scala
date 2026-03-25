package chess

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import chess.model._

class ModelFenCoverageTest extends AnyWordSpec with Matchers {

  "Board FEN parsing" should {
    "handle invalid cases nicely to reach 100% coverage" in {
      // 1. Array length missing
      Board.fromFenPlacement("8/8/8") shouldBe Left("FEN placement must contain 8 ranks")
      
      // 2. Invalid digit for empty square
      Board.fromFenPlacement("9/8/8/8/8/8/8/8") shouldBe Left("Invalid empty-square count '9' in FEN")
      
      // 3. Exceeding 8 files with empty squares 
      Board.fromFenPlacement("45/8/8/8/8/8/8/8") shouldBe Left("Rank '45' exceeds 8 files in FEN")
      
      // 4. Exceeding 8 files with piece chars
      Board.fromFenPlacement("ppppppppp/8/8/8/8/8/8/8") shouldBe Left("Rank 'ppppppppp' exceeds 8 files in FEN")
      
      // 5. Invalid piece character
      Board.fromFenPlacement("x7/8/8/8/8/8/8/8") shouldBe Left("Invalid piece 'x' in FEN")
      
      // 6. Rank has less than 8 squares described
      Board.fromFenPlacement("7/8/8/8/8/8/8/8") shouldBe Left("Rank '7' must describe exactly 8 files in FEN")
      
      // Check success edge case of fully empty fen placement: 8/8/8/8/8/8/8/8
      Board.fromFenPlacement("8/8/8/8/8/8/8/8").isRight shouldBe true
    }
  }

  "GameState FEN parsing" should {
    "fail defensively on corrupt FEN inputs" in {
      GameState.fromFen("invalid fen format") shouldBe Left("FEN must contain 6 space-separated fields")
      GameState.fromFen("invalid/placement 8 8 8 8 8 8") shouldBe Left("FEN placement must contain 8 ranks")
      GameState.fromFen("8/8/8/8/8/8/8/8 invalid_color - - 0 1") shouldBe Left("Invalid active color in FEN")
      GameState.fromFen("8/8/8/8/8/8/8/8 w invalid_castling - 0 1") shouldBe Left("Invalid castling rights in FEN")
      GameState.fromFen("8/8/8/8/8/8/8/8 w - invalid_ep 0 1") shouldBe Left("Invalid en passant target square in FEN")
      GameState.fromFen("8/8/8/8/8/8/8/8 w - a8 0 1") shouldBe Left("Invalid en passant target rank in FEN")
      GameState.fromFen("8/8/8/8/8/8/8/8 w - - -1 1") shouldBe Left("Invalid halfmove clock in FEN")
      GameState.fromFen("8/8/8/8/8/8/8/8 w - - x 1") shouldBe Left("Invalid halfmove clock in FEN")
      GameState.fromFen("8/8/8/8/8/8/8/8 w - - 0 0") shouldBe Left("Invalid fullmove number in FEN")
      GameState.fromFen("8/8/8/8/8/8/8/8 w - - 0 x") shouldBe Left("Invalid fullmove number in FEN")
    }
  }

  "GameRules" should {
    "update castling rights properly for corners and piece movements" in {
      // White Kingside Rook moves (h1)
      val s1 = GameState.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").toOption.get
      val s2 = GameRules.applyMove(s1, Move(Pos.fromAlgebraic("h1").get, Pos.fromAlgebraic("h2").get))
      s2.castlingRights.whiteKingSide shouldBe false
      s2.castlingRights.whiteQueenSide shouldBe true
      
      // Black Queenside Rook moves (a8)
      val s3 = GameState.fromFen("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1").toOption.get
      val s4 = GameRules.applyMove(s3, Move(Pos.fromAlgebraic("a8").get, Pos.fromAlgebraic("a7").get))
      s4.castlingRights.blackQueenSide shouldBe false
      s4.castlingRights.blackKingSide shouldBe true

      // Black Kingside Rook moves (h8)
      val s5 = GameState.fromFen("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1").toOption.get
      val s6 = GameRules.applyMove(s5, Move(Pos.fromAlgebraic("h8").get, Pos.fromAlgebraic("h7").get))
      s6.castlingRights.blackKingSide shouldBe false
      s6.castlingRights.blackQueenSide shouldBe true
      
      // Capturing rooks on corners should also invalidate castling rights
      val s7 = GameState.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").toOption.get
      // White rook a1 captures black rook a8
      val s8 = GameRules.applyMove(s7, Move(Pos.fromAlgebraic("a1").get, Pos.fromAlgebraic("a8").get))
      s8.castlingRights.blackQueenSide shouldBe false
      
      val s9 = GameState.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").toOption.get
      val s10 = GameRules.applyMove(s9, Move(Pos.fromAlgebraic("h1").get, Pos.fromAlgebraic("h8").get))
      s10.castlingRights.blackKingSide shouldBe false
      
      val s11 = GameState.fromFen("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1").toOption.get
      val s12 = GameRules.applyMove(s11, Move(Pos.fromAlgebraic("h8").get, Pos.fromAlgebraic("h1").get))
      s12.castlingRights.whiteKingSide shouldBe false
    }

    "compute status for insufficient material" in {
       // K vs K
       val k_vs_k = GameState.fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get
       GameRules.computeStatus(k_vs_k) shouldBe GameStatus.Draw("insufficient material")

       // King only (edge case)
       val k_only = GameState.fromFen("8/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get
       GameRules.computeStatus(k_only) shouldBe GameStatus.Draw("insufficient material")

       // K+N vs K
       val kn_vs_k = GameState.fromFen("4k3/8/8/8/8/8/8/1N2K3 w - - 0 1").toOption.get
       GameRules.computeStatus(kn_vs_k) shouldBe GameStatus.Draw("insufficient material")
    }
  }

  "MoveGenerator" should {
    "handle isAttackedBy cases for various piece types" in {
      // White pawn attacking e4 from d3/f3
      val sLeftPawnAttack = GameState.fromFen("8/8/8/8/4k3/5P2/8/4K3 b - - 0 1").toOption.get
      MoveGenerator.isAttackedBy(sLeftPawnAttack.board, Pos.fromAlgebraic("e4").get, Color.White) shouldBe true
      
      val sRightPawnAttack = GameState.fromFen("8/8/8/8/4k3/3P4/8/4K3 b - - 0 1").toOption.get
      MoveGenerator.isAttackedBy(sRightPawnAttack.board, Pos.fromAlgebraic("e4").get, Color.White) shouldBe true

      // Knight attack
      val sKnightAttack = GameState.fromFen("8/8/8/3N4/4k3/8/8/4K3 b - - 0 1").toOption.get
      MoveGenerator.isAttackedBy(sKnightAttack.board, Pos.fromAlgebraic("e4").get, Color.White) shouldBe true

      // King attack
      val sKingAttack = GameState.fromFen("8/8/8/8/4k3/4K3/8/8 b - - 0 1").toOption.get
      MoveGenerator.isAttackedBy(sKingAttack.board, Pos.fromAlgebraic("e4").get, Color.White) shouldBe true
    }

    "castling blocked by missing rooks despite castling rights being technically true" in {
      // Castling allowed in rights, but rooks are dead on the corners
      val s = GameState.fromFen("4k3/8/8/8/8/8/8/4K3 w KQkq - 0 1").toOption.get
      val moves = MoveGenerator.legalMoves(s)
      moves.exists(m => m.from == Pos.fromAlgebraic("e1").get && math.abs(m.to.col - m.from.col) == 2) shouldBe false
      
      val s2 = GameState.fromFen("4k3/8/8/8/8/8/8/4K3 b KQkq - 0 1").toOption.get
      val moves2 = MoveGenerator.legalMoves(s2)
      moves2.exists(m => m.from == Pos.fromAlgebraic("e8").get && math.abs(m.to.col - m.from.col) == 2) shouldBe false
    }

    "handle black en-passant application to board" in {
       // White just moved e2-e4. White pawn is on e4. Black pawn on d4 can capture e3.
       val s = GameState.fromFen("8/8/8/8/3pP3/8/8/8 b - e3 0 1").toOption.get
       val moves = MoveGenerator.legalMoves(s)
       moves.contains(Move(Pos.fromAlgebraic("d4").get, Pos.fromAlgebraic("e3").get)) shouldBe true
       moves.nonEmpty shouldBe true
    }
  }
}
