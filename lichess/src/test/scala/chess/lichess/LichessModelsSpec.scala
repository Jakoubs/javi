package chess.lichess

import io.circe.parser.decode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import chess.lichess.LichessModels.*

class LichessModelsSpec extends AnyWordSpec with Matchers {

  "Lichess decoders" should {
    "reject unknown event types" in {
      val json = """{"type":"unexpectedEvent","foo":"bar"}"""
      decode[LichessEvent](json).isLeft shouldBe true
    }

    "reject unknown game event types" in {
      val json = """{"type":"unexpectedGameEvent","foo":"bar"}"""
      decode[LichessGameEvent](json).isLeft shouldBe true
    }

    "accept canonical gameState payload even when type field is missing" in {
      val json = """{"moves":"e2e4","wtime":1000,"btime":1000,"winc":0,"binc":0,"status":"started"}"""
      decode[LichessGameEvent](json).isRight shouldBe true
    }

    "accept opponentGone game events" in {
      val json = """{"type":"opponentGone","gone":true,"claimWinInSeconds":60}"""
      decode[LichessGameEvent](json) shouldBe Right(OpponentGoneEvent)
    }
  }
}
