package chess.lichess

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import io.circe.parser.decode

class LichessModelsSpec extends AnyWordSpec with Matchers {

  "LichessModels" should {
    import LichessModels.*

    "decode ChallengeEvent correctly" in {
      val json = """{"type":"challenge","challenge":{"id":"c1","challenger":{"id":"u1","username":"user1"},"variant":{"key":"standard","name":"Standard"},"speed":"blitz","perf":{"name":"blitz"}}}"""
      val res = decode[LichessEvent](json)
      res.isRight shouldBe true
      val event = res.toOption.get.asInstanceOf[ChallengeEvent]
      event.challenge.id shouldBe "c1"
      event.challenge.challenger.get.username shouldBe "user1"
    }

    "decode GameStartEvent correctly" in {
      val json = """{"type":"gameStart","game":{"id":"g1"}}"""
      val res = decode[LichessEvent](json)
      res.isRight shouldBe true
      val event = res.toOption.get.asInstanceOf[GameStartEvent]
      event.game.id shouldBe "g1"
    }

    "decode GameFinishEvent correctly" in {
      val json = """{"type":"gameFinish","game":{"id":"g1"}}"""
      val res = decode[LichessEvent](json)
      res.isRight shouldBe true
      val event = res.toOption.get.asInstanceOf[GameFinishEvent]
      event.game.id shouldBe "g1"
    }
    
    "fail decoding unknown LichessEvent type" in {
       val json = """{"type":"unknown"}"""
       val res = decode[LichessEvent](json)
       res.isLeft shouldBe true
    }

    "decode GameFullEvent correctly" in {
      val json = """{"type":"gameFull","id":"g1","variant":{"key":"standard","name":"Standard"},"clock":{"limit":180,"increment":2},"speed":"blitz","perf":{"name":"blitz"},"white":{"id":"w1"},"black":{"id":"b1"},"initialFen":"startpos","state":{"moves":"e2e4","wtime":18000,"btime":18000,"winc":2000,"binc":2000,"status":"started"}}"""
      val res = decode[LichessGameEvent](json)
      res.isRight shouldBe true
      val event = res.toOption.get.asInstanceOf[GameFullEvent]
      event.id shouldBe "g1"
      event.clock.get.limit shouldBe 180
      event.state.moves shouldBe "e2e4"
    }

    "decode GameStateUpdateEvent correctly when type is gameState" in {
      val json = """{"type":"gameState","moves":"e2e4 e7e5","wtime":18000,"btime":18000,"winc":2000,"binc":2000,"status":"started"}"""
      val res = decode[LichessGameEvent](json)
      res.isRight shouldBe true
      val event = res.toOption.get.asInstanceOf[GameStateUpdateEvent]
      event.moves shouldBe "e2e4 e7e5"
    }
    
    "decode GameStateUpdateEvent correctly when type is missing" in {
      val json = """{"moves":"e2e4 e7e5","wtime":18000,"btime":18000,"winc":2000,"binc":2000,"status":"started"}"""
      val res = decode[LichessGameEvent](json)
      res.isRight shouldBe true
      val event = res.toOption.get.asInstanceOf[GameStateUpdateEvent]
      event.moves shouldBe "e2e4 e7e5"
    }

    "fail decoding unknown LichessGameEvent type" in {
       val json = """{"type":"unknown"}"""
       val res = decode[LichessGameEvent](json)
       res.isLeft shouldBe true
    }
    
    "fail decoding LichessGameEvent when type is not string" in {
       val json = """{"type":123}"""
       val res = decode[LichessGameEvent](json)
       res.isLeft shouldBe true
    }
  }
}
