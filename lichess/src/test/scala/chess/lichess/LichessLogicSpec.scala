package chess.lichess

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import chess.model.*
import chess.util.parser.CoordinateMoveParser

class LichessLogicSpec extends AnyWordSpec with Matchers {

  "Lichess move reconstruction" should {
    "correctly reconstruct game state from a list of UCI moves" in {
      val uciMoves = "e2e4 e7e5 g1f3 b8c6 b1c3"
      val moves = uciMoves.split(" ")
      
      var state = GameState.initial
      for (m <- moves) {
        val move = CoordinateMoveParser.parse(m, state).get
        state = GameRules.applyMove(state, move)
      }
      
      state.fullMoveNumber shouldBe 3
      state.activeColor shouldBe Color.Black
      state.board.get(Pos(2, 2)) shouldBe Some(Piece(Color.White, PieceType.Knight)) // c3
      state.board.get(Pos(2, 5)) shouldBe Some(Piece(Color.Black, PieceType.Knight)) // c6
    }

    "handle pawn promotion in UCI format" in {
      // Setup a position where white can promote
      // White pawn on a7, black king on h8
      val board = Board.initial.remove(Pos(0, 1)).put(Pos(0, 6), Piece(Color.White, PieceType.Pawn))
      val state = WhiteToMove(board, CastlingRights(), None, 0, 40)
      
      val uciMove = "a7a8q"
      val move = CoordinateMoveParser.parse(uciMove, state).get
      
      move.promotion shouldBe Some(PieceType.Queen)
      val nextState = GameRules.applyMove(state, move)
      nextState.board.get(Pos(0, 7)).get.pieceType shouldBe PieceType.Queen
    }
  }
}
