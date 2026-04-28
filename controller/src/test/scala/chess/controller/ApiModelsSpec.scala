package chess.controller

import chess.model.{ClockState, MaterialInfo}
import io.circe.parser.decode
import io.circe.syntax.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class ApiModelsSpec extends AnyFunSpec with Matchers {
  describe("API model codecs") {
    it("should round-trip CommandRequest as JSON") {
      val request = CommandRequest("e2e4")
      decode[CommandRequest](request.asJson.noSpaces) shouldBe Right(request)
    }

    it("should round-trip GameStateResponse as JSON") {
      val response = GameStateResponse(
        fen = "fen",
        displayFen = "display",
        pgn = "1. e4 *",
        status = "Playing",
        activeColor = "White",
        highlights = List("e2", "e4"),
        selectedPos = Some("e2"),
        lastMove = Some("e2e4"),
        aiWhite = false,
        aiBlack = true,
        flipped = false,
        viewIndex = 1,
        historyFen = List("start", "after"),
        historyMoves = List("e4"),
        clock = Some(ClockState(1000L, 2000L, 50L, None, isActive = false)),
        capturedWhite = List("Pawn"),
        capturedBlack = List("Knight"),
        message = Some("ok"),
        training = false,
        trainingProgress = Some("done"),
        running = true,
        messageIsError = false,
        materialInfo = MaterialInfo(List("Q"), List("R"), 1, 2),
        whiteLiveMillis = 900L,
        blackLiveMillis = 1800L,
        activePgnParser = "fast",
        activeMoveParser = "san",
        opening = Some("Italian Game")
      )

      decode[GameStateResponse](response.asJson.noSpaces) shouldBe Right(response)
    }
  }
}
