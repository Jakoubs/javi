package chess.view

import chess.model.{PieceType, Color => ChessColor}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class GuiSpec extends AnyFunSpec with Matchers {

  describe("GuiHelper") {
    
    describe("formatTime") {
      it("should format standard positive milliseconds") {
        GuiHelper.formatTime(65000) shouldBe "01:05"
        GuiHelper.formatTime(1000) shouldBe "00:01"
        GuiHelper.formatTime(600000) shouldBe "10:00"
      }

      it("should handle zero and negative values as 00:00") {
        GuiHelper.formatTime(0) shouldBe "00:00"
        GuiHelper.formatTime(-5000) shouldBe "00:00"
      }
    }

    describe("pieceValue") {
      it("should return standard values for all pieces") {
        GuiHelper.pieceValue(PieceType.Pawn) shouldBe 1
        GuiHelper.pieceValue(PieceType.Knight) shouldBe 3
        GuiHelper.pieceValue(PieceType.Bishop) shouldBe 3
        GuiHelper.pieceValue(PieceType.Rook) shouldBe 5
        GuiHelper.pieceValue(PieceType.Queen) shouldBe 9
        GuiHelper.pieceValue(PieceType.King) shouldBe 0
      }
    }

    describe("calculateAdvantages") {
      it("should calculate correct advantages and symbols") {
        // White captured: 2 pawns. Black captured: nothing.
        // Black has advantage +2
        val capW = List(PieceType.Pawn, PieceType.Pawn)
        val capB = Nil
        val adv = GuiHelper.calculateAdvantages(capW, capB)
        
        adv.blackAdvantage shouldBe 2
        adv.whiteAdvantage shouldBe -2
        adv.whiteCapturedSymbols should include ("♟")
        adv.blackCapturedSymbols shouldBe ""
      }

      it("should handle net advantage when both sides have captures") {
        // White captured: Queen (9). Black captured: Rook (5).
        // Black has advantage +4
        val capW = List(PieceType.Queen)
        val capB = List(PieceType.Rook)
        val adv = GuiHelper.calculateAdvantages(capW, capB)
        
        adv.blackAdvantage shouldBe 4
        adv.whiteAdvantage shouldBe -4
      }

      it("should return 0 advantage for equal material") {
        val capW = List(PieceType.Knight)
        val capB = List(PieceType.Bishop)
        val adv = GuiHelper.calculateAdvantages(capW, capB)
        
        adv.blackAdvantage shouldBe 0
        adv.whiteAdvantage shouldBe 0
      }
    }
  }
}
