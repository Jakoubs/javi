package chess.model

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class MoveGeneratorSpec extends AnyFunSpec with Matchers {
  describe("MoveGenerator") {
    it("should generate pawn moves including double step and captures") {
      val board = Board.fromFEN("8/8/8/8/8/8/PPPPPPPP/RNBQKBNR").getOrElse(fail("FEN error"))
      val state = GameState.initial.copy(board = board)
      val moves = MoveGenerator.legalMoves(state)
      // Pawns on second rank should have 2 forward moves each, plus captures (none in initial)
      val pawnMoves = moves.filter(m => board.get(m.from).exists(_.pieceType == PieceType.Pawn))
      pawnMoves.size shouldBe 16 // 8 pawns * 2 moves each
    }

    it("should generate knight jumps over pieces") {
      val board = Board.fromFEN("8/8/8/8/8/8/8/N7").getOrElse(fail("FEN error")) // white knight on a1
      val state = GameState.initial.copy(board = board)
      val moves = MoveGenerator.legalMoves(state)
      moves.map(_.from) should contain (Pos(0,0))
      val destinations = moves.filter(_.from == Pos(0,0)).map(_.to)
      destinations.toSet shouldBe Set(Pos(1,2), Pos(2,1))
    }

    it("should generate sliding moves for bishop and stop at blockers") {
      val board = Board.fromFEN("8/8/8/3P4/2B5/8/8/8").getOrElse(fail("FEN error")) // bishop on c4, pawn on d5 blocking
      val state = GameState.initial.copy(board = board)
      val moves = MoveGenerator.legalMoves(state)
      val bishopMoves = moves.filter(_.from == Pos(2, 3)) // c4
      // Bishop can move to b5, a6, d3, e2, f1 but not beyond pawn at d5
      val expected = Set(Pos(1, 4), Pos(0, 5), Pos(3, 2), Pos(4, 1), Pos(5, 0), Pos(1, 2), Pos(0, 1))
      bishopMoves.map(_.to).toSet shouldBe expected
    }

    it("should generate castling moves when allowed") {
      val board = Board.initial
      // Clear squares between king and rook for white
      val cleared = board.remove(Pos(5,0)).remove(Pos(6,0))
      val state = GameState.initial.copy(board = cleared)
      val moves = MoveGenerator.legalMoves(state)
      moves should contain (Move(Pos(4, 0), Pos(6, 0))) // white kingside castling
    }

    it("should handle pawn promotion to all piece types") {
      val board = Board.empty.put(Pos(0, 6), Piece(Color.White, PieceType.Pawn)) // pawn at a7
      val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
      val moves = MoveGenerator.legalMoves(state)
      val a8Promotions = moves.filter(_.to == Pos(0, 7)).flatMap(_.promotion)
      a8Promotions should contain allOf (PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight)
    }

    it("should not allow castling if blocked or in check") {
      val board = Board.initial.remove(Pos(5, 0)).remove(Pos(6, 0)) // cleared f1, g1
      
      // 1. King in check
      val inCheckBoard = board.put(Pos(4, 1), Piece(Color.Black, PieceType.Rook)) // Rook on e2
      val s1 = WhiteToMove(inCheckBoard, CastlingRights(), None, 0, 1)
      MoveGenerator.legalMoves(s1).filter(m => m.from == Pos(4, 0) && m.to == Pos(6, 0)) shouldBe empty
      
      // 2. Path through check
      val pathCheckBoard = board.put(Pos(5, 1), Piece(Color.Black, PieceType.Rook)) // Rook on f2
      val s2 = WhiteToMove(pathCheckBoard, CastlingRights(), None, 0, 1)
      MoveGenerator.legalMoves(s2).filter(m => m.from == Pos(4, 0) && m.to == Pos(6, 0)) shouldBe empty
    }

    it("should correctly calculate isAttackedBy for all pieces") {
      val custom = Board.empty
        .put(Pos(4, 0), Piece(Color.White, PieceType.King))
        .put(Pos(3, 0), Piece(Color.White, PieceType.Queen))
        .put(Pos(1, 1), Piece(Color.White, PieceType.Knight))
        .put(Pos(4, 7), Piece(Color.Black, PieceType.King))
      
      // Knight attack
      MoveGenerator.isAttackedBy(custom, Pos(0, 3), Color.White) shouldBe true // a4 attacked by b2 knight
      
      // King attack
      MoveGenerator.isAttackedBy(custom, Pos(4, 1), Color.White) shouldBe true // e2 attacked by e1 king
      
      // Queen attack (sliding)
      MoveGenerator.isAttackedBy(custom, Pos(3, 3), Color.White) shouldBe true // d4 attacked by queen at d1
      
      MoveGenerator.isAttackedBy(custom, Pos(0, 1), Color.White) shouldBe false // a2 not attacked
    }

    it("should handle Black en passant capture logic") {
      val board = Board.empty
        .put(Pos(3, 3), Piece(Color.Black, PieceType.Pawn))
        .put(Pos(4, 3), Piece(Color.White, PieceType.Pawn))
      val state = BlackToMove(board, CastlingRights(), Some(Pos(4, 2)), 0, 1)
      val moves = MoveGenerator.legalMoves(state)
      val epMove = Move(Pos(3, 3), Pos(4, 2))
      moves should contain (epMove)
    }

    it("should handle Black castling and blockers") {
      val board = Board.initial.remove(Pos(5, 7)).remove(Pos(6, 7)) // clear f8, g8
      val state = BlackToMove(board, CastlingRights(), None, 0, 1)
      MoveGenerator.legalMoves(state) should contain (Move(Pos(4, 7), Pos(6, 7)))

      // Blocked
      val blockedBoard = board.put(Pos(5, 7), Piece(Color.Black, PieceType.Bishop))
      val s2 = BlackToMove(blockedBoard, CastlingRights(), None, 0, 1)
      MoveGenerator.legalMoves(s2).filter(m => m.from == Pos(4, 7) && m.to == Pos(6, 7)) shouldBe empty
    }

    it("should handle Black Queenside castling and blockers") {
      val board = Board.initial.remove(Pos(1, 7)).remove(Pos(2, 7)).remove(Pos(3, 7)) // clear b8, c8, d8
      val state = BlackToMove(board, CastlingRights(), None, 0, 1)
      MoveGenerator.legalMoves(state) should contain (Move(Pos(4, 7), Pos(2, 7)))

      // Blocked by piece on b8
      val blockedBoard = board.put(Pos(1, 7), Piece(Color.Black, PieceType.Knight))
      val s2 = BlackToMove(blockedBoard, CastlingRights(), None, 0, 1)
      MoveGenerator.legalMoves(s2).filter(m => m.from == Pos(4, 7) && m.to == Pos(2, 7)) shouldBe empty
    }
  }
}
