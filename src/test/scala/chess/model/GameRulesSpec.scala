package chess.model

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class GameRulesSpec extends AnyFunSpec with Matchers {
  describe("GameRules.applyMove") {
    it("should move a pawn forward") {
      val start = Board.fromFEN("8/8/8/8/8/8/PPPPPPPP/RNBQKBNR").getOrElse(fail("FEN error"))
      val state = GameState.initial.copy(board = start)
      val move = Move(Pos(0, 1), Pos(0, 2)) // a2 to a3
      val newState = GameRules.applyMove(state, move)
      newState.board.get(Pos(0, 2)).map(_.pieceType) shouldBe Some(PieceType.Pawn)
      newState.board.get(Pos(0, 1)) shouldBe None
    }

    it("should handle en‑passant capture") {
      // Set up position where white pawn can capture en‑passant
      val fen = "8/8/8/3pP3/8/8/8/8"
      val board = Board.fromFEN(fen).getOrElse(fail("FEN error"))
      val state = WhiteToMove(
        board = board,
        castlingRights = CastlingRights(),
        enPassantTarget = Some(Pos(3, 5)), // d6 square after black pawn double step
        halfMoveClock = 0,
        fullMoveNumber = 1,
        history = Nil
      )
      val move = Move(Pos(4, 4), Pos(3, 5)) // e5 captures d6 en‑passant
      val newState = GameRules.applyMove(state, move)
      newState.board.get(Pos(3, 5)).map(_.pieceType) shouldBe Some(PieceType.Pawn)
      newState.board.get(Pos(3, 4)) shouldBe None // captured pawn removed
    }

    it("should allow kingside castling for white") {
      val board = Board.initial
      // Clear squares between king and rook
      val cleared = board.remove(Pos(5, 0)).remove(Pos(6, 0))
      val state = GameState.initial.copy(board = cleared)
      val move = Move(Pos(4, 0), Pos(6, 0)) // e1 to g1 castling
      val newState = GameRules.applyMove(state, move)
      newState.board.get(Pos(6, 0)).map(_.pieceType) shouldBe Some(PieceType.King)
      newState.board.get(Pos(5, 0)).map(_.pieceType) shouldBe Some(PieceType.Rook)
    }

    it("should handle pawn promotion") {
      val fen = "8/8/8/8/8/8/P7/8"
      val board = Board.fromFEN(fen).getOrElse(fail("FEN error"))
      val state = GameState.initial.copy(board = board)
      val move = Move(Pos(0, 1), Pos(0, 7), Some(PieceType.Queen))
      val newState = GameRules.applyMove(state, move)
      newState.board.get(Pos(0, 7)).map(_.pieceType) shouldBe Some(PieceType.Queen)
    }

    it("should detect checkmate") {
      // Fool's mate position after 1.f3 e5 2.g4 Qh4#
      val state = GameState.fromFen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 0 3").getOrElse(fail("FEN error"))
      val status = GameRules.computeStatus(state)
      status shouldBe GameStatus.Checkmate(Color.White)
    }

    it("should detect stalemate") {
      // Classic stalemate position (White to move, King at a1, Black Queen at c2)
      val fen = "k7/8/8/8/8/8/2q5/K7 w - - 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail("FEN error"))
      val status = GameRules.computeStatus(state)
      status shouldBe GameStatus.Stalemate
    }

    it("should detect draw by 50‑move rule") {
      val state = GameState.initial.copy(halfMoveClock = 100)
      GameRules.computeStatus(state) shouldBe GameStatus.Draw("50-move rule")
    }

    it("should detect draw by threefold repetition") {
      val s1 = GameState.initial
      val s2 = GameRules.applyMove(s1, Move(Pos(1, 0), Pos(2, 2))) // Na3
      val s3 = GameRules.applyMove(s2, Move(Pos(1, 7), Pos(2, 5))) // Na6
      val s4 = GameRules.applyMove(s3, Move(Pos(2, 2), Pos(1, 0))) // back to initial board
      val s5 = GameRules.applyMove(s4, Move(Pos(2, 5), Pos(1, 7))) // back to initial state
      
      val s6 = GameRules.applyMove(s5, Move(Pos(1, 0), Pos(2, 2))) // 2nd repeat
      val s7 = GameRules.applyMove(s6, Move(Pos(1, 7), Pos(2, 5)))
      val s8 = GameRules.applyMove(s7, Move(Pos(2, 2), Pos(1, 0))) // back
      val s9 = GameRules.applyMove(s8, Move(Pos(2, 5), Pos(1, 7))) // back
      
      GameRules.computeStatus(s9) shouldBe GameStatus.Draw("threefold repetition")
    }

    it("should detect draw by insufficient material") {
      val kk = GameState.fromFen("k7/8/8/8/8/8/8/K7 w - - 0 1").getOrElse(fail(""))
      GameRules.computeStatus(kk) shouldBe GameStatus.Draw("insufficient material")
      
      val kbk = GameState.fromFen("k7/8/8/8/8/8/8/K1B5 w - - 0 1").getOrElse(fail(""))
      GameRules.computeStatus(kbk) shouldBe GameStatus.Draw("insufficient material")
      
      val knk = GameState.fromFen("k7/8/8/8/8/8/8/K1N5 w - - 0 1").getOrElse(fail(""))
      GameRules.computeStatus(knk) shouldBe GameStatus.Draw("insufficient material")

      // K+B vs K+B (same color bishops)
      // (0,0) is even, (1,1) is even -> same color bishops draw
      val kbbkSame = GameState.fromFen("k7/8/8/8/8/8/1b6/B6K w - - 0 1").getOrElse(fail(""))
      GameRules.computeStatus(kbbkSame) shouldBe GameStatus.Draw("insufficient material")

      // K+B vs K+B (different color bishops) - NOT a draw
      // (0,0) is even, (1,0) is odd
      val kbbkDiff = GameState.fromFen("k7/8/8/8/8/8/b7/B6K w - - 0 1").getOrElse(fail(""))
      GameRules.computeStatus(kbbkDiff) shouldBe GameStatus.Playing

      // King count < 2 (missing king) - add a pawn so moves is not empty
      val noKings = Board.empty.put(Pos(0, 1), Piece(Color.White, PieceType.Pawn))
      val stateNoKings = WhiteToMove(noKings, CastlingRights(), None, 0, 1)
      GameRules.computeStatus(stateNoKings) shouldBe GameStatus.Draw("insufficient material")
      
      // More than 4 pieces
      val tooMany = GameState.fromFen("k7/8/8/8/8/8/PPPPPPPP/K7 w - - 0 1").getOrElse(fail(""))
      GameRules.computeStatus(tooMany) shouldBe GameStatus.Playing
    }

    it("should update castling rights when pieces move or are captured") {
      val s = GameState.initial
      // King moves
      val s1 = GameRules.applyMove(s, Move(Pos(4, 0), Pos(4, 1)))
      s1.castlingRights.whiteKingSide shouldBe false
      s1.castlingRights.whiteQueenSide shouldBe false
      
      // Rook moves
      val s2 = GameRules.applyMove(s, Move(Pos(0, 0), Pos(0, 1)))
      s2.castlingRights.whiteQueenSide shouldBe false
      
      // Rook captured in corner
      val board = Board.initial.put(Pos(0, 7), Piece(Color.White, PieceType.Rook)) // White rook on a8
      val sc = GameState.initial.copy(board = board).withActiveColor(Color.White)
      val sc1 = GameRules.applyMove(sc, Move(Pos(0, 7), Pos(7, 7))) // Cap black rook on h8? No, move to h8
      sc1.castlingRights.blackKingSide shouldBe false
    }

    it("should set en‑passant target on double pawn step") {
      val s = GameState.initial
      val s1 = GameRules.applyMove(s, Move(Pos(3, 1), Pos(3, 3))) // d2d4
      s1.enPassantTarget shouldBe Some(Pos(3, 2))
    }

    it("should handle castling and rights for Black and Queenside") {
      // 1. White Queenside
      val wqBoard = Board.initial.remove(Pos(1, 0)).remove(Pos(2, 0)).remove(Pos(3, 0))
      val wqState = WhiteToMove(wqBoard, CastlingRights(), None, 0, 1)
      val wqNext = GameRules.applyMove(wqState, Move(Pos(4, 0), Pos(2, 0)))
      wqNext.board.get(Pos(3, 0)).get.pieceType shouldBe PieceType.Rook
      wqNext.castlingRights.whiteQueenSide shouldBe false

      // 2. Black Kingside
      val bkBoard = Board.initial.remove(Pos(5, 7)).remove(Pos(6, 7))
      val bkState = BlackToMove(bkBoard, CastlingRights(), None, 0, 1)
      val bkNext = GameRules.applyMove(bkState, Move(Pos(4, 7), Pos(6, 7)))
      bkNext.board.get(Pos(5, 7)).get.pieceType shouldBe PieceType.Rook
      bkNext.castlingRights.blackKingSide shouldBe false
      
      // 3. Black King move
      val bmNext = GameRules.applyMove(bkState, Move(Pos(4, 7), Pos(4, 6)))
      bmNext.castlingRights.blackKingSide shouldBe false
      bmNext.castlingRights.blackQueenSide shouldBe false
    }

    it("should handle all rook corner cases for rights") {
      // Rook move from h1
      val s1 = GameRules.applyMove(GameState.initial, Move(Pos(7, 0), Pos(7, 1)))
      s1.castlingRights.whiteKingSide shouldBe false
      
      // Rook move from a8
      val s2 = GameRules.applyMove(GameState.initial.withActiveColor(Color.Black), Move(Pos(0, 7), Pos(0, 6)))
      s2.castlingRights.blackQueenSide shouldBe false
      
      // Rook move from h8
      val s3 = GameRules.applyMove(GameState.initial.withActiveColor(Color.Black), Move(Pos(7, 7), Pos(7, 6)))
      s3.castlingRights.blackKingSide shouldBe false
      
      // Capture rooks in corners
      val whiteTurn = WhiteToMove(Board.initial, CastlingRights(), None, 0, 1)
      GameRules.applyMove(whiteTurn, Move(Pos(3, 0), Pos(0, 7))).castlingRights.blackQueenSide shouldBe false
      GameRules.applyMove(whiteTurn, Move(Pos(3, 0), Pos(7, 7))).castlingRights.blackKingSide shouldBe false
      
      val blackTurn = BlackToMove(Board.initial, CastlingRights(), None, 0, 1)
      GameRules.applyMove(blackTurn, Move(Pos(3, 7), Pos(0, 0))).castlingRights.whiteQueenSide shouldBe false
      GameRules.applyMove(blackTurn, Move(Pos(3, 7), Pos(7, 0))).castlingRights.whiteKingSide shouldBe false
    }

    it("should handle en passant for Black") {
      val board = Board.empty.put(Pos(3, 3), Piece(Color.Black, PieceType.Pawn)).put(Pos(4, 3), Piece(Color.White, PieceType.Pawn))
      val state = BlackToMove(board, CastlingRights(), Some(Pos(4, 2)), 0, 1)
      val next = GameRules.applyMove(state, Move(Pos(3, 3), Pos(4, 2)))
      next.board.get(Pos(4, 3)) shouldBe None
    }
  }
}
