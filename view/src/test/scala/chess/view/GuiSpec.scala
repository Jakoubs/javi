package chess.view

import chess.model.{PieceType, GameState, Color => ChessColor, Pos}
import chess.model.materialInfo

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class GuiSpec extends AnyFunSpec with Matchers {

  describe("Gui Formatting") {
    
    describe("formatTime") {
      it("should format standard positive milliseconds") {
        Gui.formatTime(65000) shouldBe "01:05"
        Gui.formatTime(1000) shouldBe "00:01"
        Gui.formatTime(600000) shouldBe "10:00"
      }

      it("should handle zero and negative values as 00:00") {
        Gui.formatTime(0) shouldBe "00:00"
        Gui.formatTime(-5000) shouldBe "00:00"
      }
    }
  }

  describe("Material Logic") {

    describe("pieceValue") {
      it("should return standard values for all pieces") {
        PieceType.pieceValue(PieceType.Pawn) shouldBe 1
        PieceType.pieceValue(PieceType.Knight) shouldBe 3
        PieceType.pieceValue(PieceType.Bishop) shouldBe 3
        PieceType.pieceValue(PieceType.Rook) shouldBe 5
        PieceType.pieceValue(PieceType.Queen) shouldBe 9
        PieceType.pieceValue(PieceType.King) shouldBe 0
      }
    }

    describe("calculateAdvantages (via GameState.materialInfo)") {
      it("should calculate correct advantages and symbols") {
        val state = GameState.initial
        // Remove 2 white pawns directly from the board state
        val modBoard = state.board.remove(Pos(0, 1)).remove(Pos(1, 1))
        val modState = chess.model.WhiteToMove(modBoard, state.castlingRights, state.enPassantTarget, state.halfMoveClock, state.fullMoveNumber)

        val adv = modState.materialInfo
        
        adv.blackAdvantage shouldBe 2
        adv.whiteAdvantage shouldBe 0
        adv.whiteCapturedSymbols should contain ("♟")
        adv.blackCapturedSymbols.isEmpty shouldBe true
      }

      it("should handle net advantage when both sides have captures") {
        val state = GameState.initial
        // White loses a Queen (9), Black loses a Rook (5) => Black has advantage +4
        val modBoard = state.board.remove(Pos(3, 0)).remove(Pos(0, 7))
        val modState = chess.model.WhiteToMove(modBoard, state.castlingRights, state.enPassantTarget, state.halfMoveClock, state.fullMoveNumber)

        val adv = modState.materialInfo
        
        adv.blackAdvantage shouldBe 4
        adv.whiteAdvantage shouldBe 0
      }

      it("should return 0 advantage for equal material") {
        val state = GameState.initial
        // White loses Knight (3), Black loses Bishop (3) => 0 advantage
        val modBoard = state.board.remove(Pos(1, 0)).remove(Pos(2, 7))
        val modState = chess.model.WhiteToMove(modBoard, state.castlingRights, state.enPassantTarget, state.halfMoveClock, state.fullMoveNumber)

        val adv = modState.materialInfo
        
        adv.blackAdvantage shouldBe 0
        adv.whiteAdvantage shouldBe 0
      }
    }
  }
}
