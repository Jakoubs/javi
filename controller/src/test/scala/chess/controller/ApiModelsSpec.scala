package chess.controller

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import io.circe.parser.decode
import io.circe.syntax.*
import chess.model.{ClockState, MaterialInfo}

class ApiModelsSpec extends AnyWordSpec with Matchers {

  "CommandRequest" should {
    import CommandRequest.codec

    "encode and decode properly" in {
      val req = CommandRequest("start e2e4")
      val json = req.asJson.noSpaces
      val expectedJson = """{"command":"start e2e4"}"""
      json shouldBe expectedJson

      val decoded = decode[CommandRequest](json)
      decoded shouldBe Right(req)
    }
  }

  "GameStateResponse" should {
    import GameStateResponse.codec

    "encode and decode properly" in {
      val state = GameStateResponse(
        fen = "start",
        displayFen = "start_disp",
        pgn = "1. e4",
        status = "started",
        activeColor = "white",
        highlights = List("e2", "e4"),
        selectedPos = Some("e2"),
        lastMove = Some("e2e4"),
        aiWhite = false,
        aiBlack = true,
        flipped = false,
        viewIndex = 0,
        historyFen = List("start", "fen2"),
        historyMoves = List("e2e4"),
        clock = Some(ClockState(1000L, 1000L, 200L, Some(0L), false)),
        capturedWhite = List("p", "n"),
        capturedBlack = List("P"),
        message = Some("Hello"),
        training = false,
        trainingProgress = None,
        running = true,
        messageIsError = false,
        materialInfo = MaterialInfo(List("p", "n"), List("P"), 1, 0),
        whiteLiveMillis = 1000L,
        blackLiveMillis = 1000L,
        activePgnParser = "fast",
        activeMoveParser = "algebraic"
      )
      
      val json = state.asJson.noSpaces
      val decoded = decode[GameStateResponse](json)
      
      decoded shouldBe Right(state)
    }
  }
}
