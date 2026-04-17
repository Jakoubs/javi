package chess.model

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class BoardSpec extends AnyFunSpec with Matchers {
  describe("Board") {
    it("should have correct initial FEN placement") {
      val initialBoard = Board.initial
      val fen = initialBoard.toFenPlacement
      fen shouldBe "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
    }

    it("should create an empty board with empty FEN") {
      val emptyBoard = Board.empty
      emptyBoard.toFenPlacement shouldBe "8/8/8/8/8/8/8/8"
    }

    it("should round‑trip FEN placement to board and back") {
      val fen = "r1bqkbnr/pppppppp/2n5/8/8/2N5/PPPPPPPP/R1BQKBNR"
      val boardEither = Board.fromFEN(fen)
      boardEither shouldBe a[Right[_, _]]
      val board = boardEither.getOrElse(fail("Could not parse board from FEN"))
      board.toFenPlacement shouldBe fen
    }

    it("should correctly identify pieces and empty squares") {
      val board = Board.initial
      board.isOccupied(Pos(0, 0)) shouldBe true
      board.isEmpty(Pos(0, 3)) shouldBe true
      board.isOccupiedBy(Pos(0, 0), Color.White) shouldBe true
    }

    it("should find the king") {
      Board.initial.findKing(Color.White) shouldBe Some(Pos(4, 0))
      Board.initial.findKing(Color.Black) shouldBe Some(Pos(4, 7))
      Board.empty.findKing(Color.White) shouldBe None
    }
    it("should handle movePiece from an empty square") {
      val board = Board.empty
      board.movePiece(Pos(0, 0), Pos(0,1)) shouldBe board
    }

    describe("fromFen error handling") {
      it("should fail on invalid rank count") {
         Board.fromFenPlacement("8/8/8/8/8/8/8").isLeft shouldBe true
      }
      it("should fail on invalid empty-square count") {
        Board.fromFenPlacement("9/8/8/8/8/8/8/8").isLeft shouldBe true
      }
      it("should fail on zero empty-square digit in rank") {
        // '0' is a digit but emptySquares < 1 — the other uncovered branch
        Board.fromFenPlacement("p0pppppp/8/8/8/8/8/8/8").isLeft shouldBe true
      }
      it("should fail on rank exceeding 8 files (empty squares)") {
        Board.fromFenPlacement("45/8/8/8/8/8/8/8").isLeft shouldBe true
      }
      it("should fail on invalid piece character") {
        Board.fromFenPlacement("p7x/8/8/8/8/8/8/8").isLeft shouldBe true
      }
      it("should fail on rank exceeding 8 files (piece)") {
        Board.fromFenPlacement("p7p/8/8/8/8/8/8/8").isLeft shouldBe true
      }
      it("should fail on rank describing fewer than 8 files") {
        Board.fromFenPlacement("7/8/8/8/8/8/8/8").isLeft shouldBe true
      }
    }
  }

  describe("GameState") {
    it("should round‑trip full FEN with en passant") {
      val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      val state = GameState.fromFen(fen).getOrElse(fail("FEN error"))
      state.toFen shouldBe fen
      state.enPassantTarget shouldBe Some(Pos(4, 2))
    }

    it("should correctly handle state copies and color changes for BlackToMove") {
      val s1 = GameState.initial.withActiveColor(Color.Black)
      s1 shouldBe a[BlackToMove]
      
      // Cover (BlackToMove, Black) branch in withActiveColor
      s1.withActiveColor(Color.Black) shouldBe s1

      val s2 = s1.copy(fullMoveNumber = 2)
      s2.fullMoveNumber shouldBe 2
      s2 shouldBe a[BlackToMove]

      val s3 = s1.withActiveColor(Color.White)
      s3 shouldBe a[WhiteToMove]
    }

    it("should accumulate history") {
      val s = GameState.initial
      val sWithHistory = s.withHistory
      sWithHistory.history shouldBe List(s)

      val black = s.withActiveColor(Color.Black)
      black.withHistory.history shouldBe List(black)
    }

    it("should identify multiple captured pieces") {
      val board = Board.empty
        .put(Pos(4,0), Piece(Color.White, PieceType.King))
        .put(Pos(4,7), Piece(Color.Black, PieceType.King))
      val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
      
      val whiteCaptured = state.capturedPieces(Color.White)
      whiteCaptured.size shouldBe 15
      // Verify counts for all types
      whiteCaptured.count(_ == PieceType.Pawn) shouldBe 8
      whiteCaptured.count(_ == PieceType.Knight) shouldBe 2
      whiteCaptured.count(_ == PieceType.Bishop) shouldBe 2
      whiteCaptured.count(_ == PieceType.Rook) shouldBe 2
      whiteCaptured.count(_ == PieceType.Queen) shouldBe 1
      
      val blackCaptured = state.capturedPieces(Color.Black)
      blackCaptured.size shouldBe 15
    }

    describe("fromFen error handling") {
      it("should fail on invalid active color") {
        GameState.fromFen("8/8/8/8/8/8/8/8 x - - 0 1").isLeft shouldBe true
      }
      it("should fail on invalid en passant target rank") {
        GameState.fromFen("8/8/8/8/8/8/8/8 w - e4 0 1").isLeft shouldBe true
      }
      it("should fail on invalid en passant target square") {
        GameState.fromFen("8/8/8/8/8/8/8/8 w - x9 0 1").isLeft shouldBe true
      }
      it("should fail on invalid numbers") {
        GameState.fromFen("8/8/8/8/8/8/8/8 w - - x 1").isLeft shouldBe true
        GameState.fromFen("8/8/8/8/8/8/8/8 w - - 0 x").isLeft shouldBe true
        GameState.fromFen("8/8/8/8/8/8/8/8 w - - -1 1").isLeft shouldBe true // negative
        GameState.fromFen("8/8/8/8/8/8/8/8 w - - 0 0").isLeft shouldBe true // non-positive fullmove
      }
      it("should fail on incomplete FEN") {
        GameState.fromFen("8/8/8/8/8/8/8/8 w -").isLeft shouldBe true
      }
      it("should fail on invalid castling rights") {
        GameState.fromFen("8/8/8/8/8/8/8/8 w XYZ - 0 1").isLeft shouldBe true
      }
      it("should fail if board placement is invalid") {
        GameState.fromFen("9/8/8/8/8/8/8/8 w - - 0 1").isLeft shouldBe true
      }
    }
  }
}
