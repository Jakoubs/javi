package chess.lichess

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import fs2.Stream
import io.circe.syntax.*
import cats.syntax.all.*
import org.http4s.implicits.*
import java.nio.file.{Files, Paths}

class LichessClientSpec extends AnyWordSpec with Matchers {

  "LichessClient" should {
    
    def createClient(routes: HttpRoutes[IO]): Client[IO] = {
      Client.fromHttpApp(routes.orNotFound)
    }

    "stream events correctly" in {
      val eventStreamResponse = """{"type":"gameStart","game":{"id":"12345"}}""" + "\n" +
                                """{"type":"challenge","challenge":{"id":"abc","challenger":{"id":"test","username":"testuser"},"variant":{"key":"standard","name":"Standard"},"speed":"blitz","perf":{"name":"blitz"}}}""" + "\n" +
                                """invalid_json""" + "\n" +
                                """{"type":"unknown"}"""

      val routes = HttpRoutes.of[IO] {
        case GET -> Root / "api" / "stream" / "event" =>
          Ok(Stream.eval(IO(eventStreamResponse)).through(fs2.text.utf8.encode))
      }
      
      val client = new LichessClient("dummy_token", createClient(routes))
      val events = client.streamEvents().compile.toList.unsafeRunSync()
      
      events should have size 2
      events.head shouldBe GameStartEvent(GameId("12345"))
      events(1).isInstanceOf[ChallengeEvent] shouldBe true
    }

    "handle event stream failure" in {
       val routes = HttpRoutes.of[IO] {
        case GET -> Root / "api" / "stream" / "event" =>
          InternalServerError()
      }
      val client = new LichessClient("dummy_token", createClient(routes))
      val events = client.streamEvents().compile.toList.unsafeRunSync()
      events shouldBe empty
    }

    "stream game events correctly" in {
      val gameStreamResponse = """{"type":"gameFull","id":"123","variant":{"key":"standard","name":"Standard"},"speed":"blitz","perf":{"name":"blitz"},"white":{"id":"p1"},"black":{"id":"p2"},"initialFen":"start","state":{"moves":"","wtime":100,"btime":100,"winc":0,"binc":0,"status":"started"}}""" + "\n" +
                               """{"type":"gameState","moves":"e2e4","wtime":90,"btime":90,"winc":0,"binc":0,"status":"started"}""" + "\n" +
                               """invalid_json""" + "\n" +
                               """{"moves":"e2e4 e7e5","wtime":80,"btime":80,"winc":0,"binc":0,"status":"started"}"""

      val routes = HttpRoutes.of[IO] {
        case GET -> Root / "api" / "bot" / "game" / "stream" / "123" =>
          Ok(Stream.eval(IO(gameStreamResponse)).through(fs2.text.utf8.encode))
      }
      val client = new LichessClient("dummy_token", createClient(routes))
      val events = client.streamGame("123").compile.toList.unsafeRunSync()
      
      events should have size 3
      events.head.isInstanceOf[GameFullEvent] shouldBe true
      events(1).isInstanceOf[GameStateUpdateEvent] shouldBe true
      events(2).isInstanceOf[GameStateUpdateEvent] shouldBe true
    }

    "handle game stream failure" in {
       val routes = HttpRoutes.of[IO] {
        case GET -> Root / "api" / "bot" / "game" / "stream" / "123" =>
          InternalServerError()
      }
      val client = new LichessClient("dummy_token", createClient(routes))
      val events = client.streamGame("123").compile.toList.unsafeRunSync()
      events shouldBe empty
    }

    "accept challenge" in {
      val routes = HttpRoutes.of[IO] {
        case POST -> Root / "api" / "challenge" / "abc" / "accept" => IO(Response(Status.Ok))
      }
      val client = new LichessClient("dummy", createClient(routes))
      client.acceptChallenge("abc").unsafeRunSync() shouldBe true
    }

    "decline challenge" in {
      val routes = HttpRoutes.of[IO] {
        case POST -> Root / "api" / "challenge" / "abc" / "decline" => IO(Response(Status.Ok))
      }
      val client = new LichessClient("dummy", createClient(routes))
      client.declineChallenge("abc").unsafeRunSync() shouldBe true
    }

    "make move success" in {
      val routes = HttpRoutes.of[IO] {
        case POST -> Root / "api" / "bot" / "game" / "123" / "move" / "e2e4" => IO(Response(Status.Ok))
      }
      val client = new LichessClient("dummy", createClient(routes))
      client.makeMove("123", "e2e4").unsafeRunSync() shouldBe true
    }
    
    "make move failure" in {
      val routes = HttpRoutes.of[IO] {
        case POST -> Root / "api" / "bot" / "game" / "123" / "move" / "e2e4" => BadRequest()
      }
      val client = new LichessClient("dummy", createClient(routes))
      client.makeMove("123", "e2e4").unsafeRunSync() shouldBe false
    }

    "challenge bot" in {
      val routes = HttpRoutes.of[IO] {
        case POST -> Root / "api" / "challenge" / "botId" => IO(Response(Status.Ok))
      }
      val client = new LichessClient("dummy", createClient(routes))
      client.challengeBot("botId").unsafeRunSync() shouldBe true
    }

    "get account info success" in {
      val jsonResp = """{"id":"javi","username":"javibot","title":"BOT"}"""
      val routes = HttpRoutes.of[IO] {
        case GET -> Root / "api" / "account" => Ok(jsonResp)
      }
      val client = new LichessClient("dummy", createClient(routes))
      val info = client.getAccountInfo().unsafeRunSync()
      info shouldBe Some(LichessUser("javi", "javibot", Some("BOT")))
    }

    "get account info failure" in {
      val routes = HttpRoutes.of[IO] {
        case GET -> Root / "api" / "account" => NotFound()
      }
      val client = new LichessClient("dummy", createClient(routes))
      client.getAccountInfo().unsafeRunSync() shouldBe None
    }

    "load token correctly" in {
      val tokenPath = Paths.get("lichess.token")
      val isNew = !Files.exists(tokenPath)
      if (isNew) Files.write(tokenPath, "test_token".getBytes)
      
      try {
        LichessClient.loadToken() should not be empty
      } finally {
        if (isNew) Files.delete(tokenPath)
      }
    }
    
    "load token throw exception if not found" in {
      val tokenPath = Paths.get("lichess.token")
      var existed = false
      var tempContent = Array.emptyByteArray
      if (Files.exists(tokenPath)) {
        existed = true
        tempContent = Files.readAllBytes(tokenPath)
        Files.delete(tokenPath)
      }
      
      try {
        assertThrows[RuntimeException] {
          LichessClient.loadToken()
        }
      } finally {
        if (existed) Files.write(tokenPath, tempContent)
      }
    }
  }
}
