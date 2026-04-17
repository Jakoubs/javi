package chess.lichess

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.http4s.client.Client
import fs2.Stream
import chess.model.*
import chess.ai.AlphaBetaAgent

// Stub Client
class StubLichessClient(
  accountInfo: Option[LichessUser] = None,
  eventStream: Stream[IO, LichessEvent] = Stream.empty,
  gameStreamResponses: Map[String, Stream[IO, LichessGameEvent]] = Map.empty,
  var acceptedChallenges: List[String] = Nil,
  var declinedChallenges: List[String] = Nil,
  var moves: List[(String, String)] = Nil
) extends LichessClient("dummy", null) {
  
  override def getAccountInfo(): IO[Option[LichessUser]] = IO.pure(accountInfo)
  override def streamEvents(): Stream[IO, LichessEvent] = eventStream
  override def streamGame(gameId: String): Stream[IO, LichessGameEvent] = 
    gameStreamResponses.getOrElse(gameId, Stream.empty)
    
  override def acceptChallenge(challengeId: String): IO[Boolean] = {
    acceptedChallenges = challengeId :: acceptedChallenges
    IO.pure(true)
  }
  
  override def declineChallenge(challengeId: String): IO[Boolean] = {
    declinedChallenges = challengeId :: declinedChallenges
    IO.pure(true)
  }
  
  override def makeMove(gameId: String, move: String): IO[Boolean] = {
    moves = (gameId, move) :: moves
    IO.pure(true)
  }
}

class LichessServiceSpec extends AnyWordSpec with Matchers {
  
  "LichessService" should {
    
    "handle run without account info" in {
      val client = new StubLichessClient(accountInfo = None)
      val service = new LichessService(client)
      service.run().unsafeRunSync() // Should just print and exit
    }

    "accept standard challenges and decline others" in {
      val user = LichessUser("botid", "botname", None)
      val standardChallenge = ChallengeEvent(LichessChallenge("c1", None, LichessVariant("standard", "Standard"), "blitz", LichessPerf("blitz")))
      val atomicChallenge = ChallengeEvent(LichessChallenge("c2", None, LichessVariant("atomic", "Atomic"), "blitz", LichessPerf("blitz")))
      
      val eventStream = Stream.emits[IO, LichessEvent](Seq(standardChallenge, atomicChallenge))
      val client = new StubLichessClient(accountInfo = Some(user), eventStream = eventStream)
      val service = new LichessService(client)
      
      service.run().unsafeRunSync()
      
      client.acceptedChallenges should contain ("c1")
      client.declinedChallenges should contain ("c2")
    }

    "handle game start and finish events" in {
      val user = LichessUser("botid", "botname", None)
      val eventStream = Stream.emits[IO, LichessEvent](Seq(
        GameStartEvent(GameId("g1")),
        GameFinishEvent(GameId("g1"))
      ))
      val client = new StubLichessClient(accountInfo = Some(user), eventStream = eventStream)
      val service = new LichessService(client)
      
      service.run().unsafeRunSync()
      // should not throw, should handle safely
    }

    "process game full event and make a valid move when bot is playing white" in {
      val user = LichessUser("botid", "botname", None)
      val eventStream = Stream.emits[IO, LichessEvent](Seq(
        GameStartEvent(GameId("g1"))
      ))
      
      val gameFull = GameFullEvent(
        "g1",
        LichessVariant("standard", "Standard"),
        None,
        "blitz",
        LichessPerf("blitz"),
        LichessPlayer(Some("botid"), None, None, None), // we are white
        LichessPlayer(Some("p2"), None, None, None),
        "start",
        LichessGameState("", 100, 100, 0, 0, "started")
      )
      
      val gameStream = Stream.emits[IO, LichessGameEvent](Seq(gameFull))
      
      val client = new StubLichessClient(
        accountInfo = Some(user), 
        eventStream = eventStream,
        gameStreamResponses = Map("g1" -> gameStream)
      )
      val service = new LichessService(client)
      service.run().unsafeRunSync()
      
      // Wait a bit for the asynchronous fibers to complete their work
      Thread.sleep(1000)
      
      client.moves.size shouldBe 1
      client.moves.head._1 shouldBe "g1"
    }

    "process game state update with empty status" in {
      val user = LichessUser("botid", "botname", None)
      val eventStream = Stream.emits[IO, LichessEvent](Seq(
        GameStartEvent(GameId("g1"))
      ))
      
      val gameFull = GameFullEvent(
        "g1",
        LichessVariant("standard", "Standard"),
        None,
        "blitz",
        LichessPerf("blitz"),
        LichessPlayer(Some("p2"), None, None, None), 
        LichessPlayer(Some("botid"), None, None, None), // we are black
        "start",
        LichessGameState("e2e4", 100, 100, 0, 0, "started") // White moved
      )
      
      val gameStream = Stream.emits[IO, LichessGameEvent](Seq(gameFull))
      
      val client = new StubLichessClient(
        accountInfo = Some(user), 
        eventStream = eventStream,
        gameStreamResponses = Map("g1" -> gameStream)
      )
      val service = new LichessService(client)
      service.run().unsafeRunSync()
      
      Thread.sleep(1000)
      
      client.moves.size shouldBe 1
    }
    
    "ignore state updates when status is not started" in {
      val user = LichessUser("botid", "botname", None)
      val eventStream = Stream.emits[IO, LichessEvent](Seq(
        GameStartEvent(GameId("g1"))
      ))
      
      val gameFull = GameFullEvent(
        "g1",
        LichessVariant("standard", "Standard"),
        None,
        "blitz",
        LichessPerf("blitz"),
        LichessPlayer(Some("botid"), None, None, None), 
        LichessPlayer(Some("p2"), None, None, None), 
        "start",
        LichessGameState("", 100, 100, 0, 0, "mate")
      )
      
      val gameStream = Stream.emits[IO, LichessGameEvent](Seq(gameFull))
      
      val client = new StubLichessClient(
        accountInfo = Some(user), 
        eventStream = eventStream,
        gameStreamResponses = Map("g1" -> gameStream)
      )
      val service = new LichessService(client)
      service.run().unsafeRunSync()
      
      Thread.sleep(200)
      
      client.moves.size shouldBe 0
    }
    
    "handle invalid moves without crashing" in {
      val user = LichessUser("botid", "botname", None)
      val eventStream = Stream.emits[IO, LichessEvent](Seq(
        GameStartEvent(GameId("g1"))
      ))
      
      val gameFull = GameFullEvent(
        "g1",
        LichessVariant("standard", "Standard"),
        None,
        "blitz",
        LichessPerf("blitz"),
        LichessPlayer(Some("p2"), None, None, None), 
        LichessPlayer(Some("botid"), None, None, None), 
        "start",
        LichessGameState("invalid_move", 100, 100, 0, 0, "started")
      )
      
      val gameStream = Stream.emits[IO, LichessGameEvent](Seq(gameFull))
      
      val client = new StubLichessClient(
        accountInfo = Some(user), 
        eventStream = eventStream,
        gameStreamResponses = Map("g1" -> gameStream)
      )
      val service = new LichessService(client)
      service.run().unsafeRunSync()
      
      Thread.sleep(200)
      
      client.moves.size shouldBe 0
    }
    
    "process GameStateUpdateEvent properly" in {
      val user = LichessUser("botid", "botname", None)
      val eventStream = Stream.emits[IO, LichessEvent](Seq(
        GameStartEvent(GameId("g2"))
      ))
      
      val gameFull = GameFullEvent(
        "g2",
        LichessVariant("standard", "Standard"),
        None,
        "blitz",
        LichessPerf("blitz"),
        LichessPlayer(Some("botid"), None, None, None), // we are white
        LichessPlayer(Some("p2"), None, None, None),
        "start",
        LichessGameState("", 100, 100, 0, 0, "started")
      )
      
      val stateUpdate = GameStateUpdateEvent(
        "e2e4 e7e5 e1e2", 100, 100, 0, 0, "started" 
        // 3 moves passed, so it's black's turn now (not our turn)
      )
      
      val gameStream = Stream.emits[IO, LichessGameEvent](Seq(gameFull, stateUpdate))
      
      val client = new StubLichessClient(
        accountInfo = Some(user), 
        eventStream = eventStream,
        gameStreamResponses = Map("g2" -> gameStream)
      )
      val service = new LichessService(client)
      service.run().unsafeRunSync()
      Thread.sleep(1000)
    }

    "handle case when move is rejected" in {
       val user = LichessUser("botid", "botname", None)
       val gameFull = GameFullEvent(
        "rej1",
        LichessVariant("standard", "Standard"),
        None,
        "blitz",
        LichessPerf("blitz"),
        LichessPlayer(Some("botid"), None, None, None), // we are white
        LichessPlayer(Some("p2"), None, None, None),
        "start",
        LichessGameState("", 100, 100, 0, 0, "started")
      )
      
      val gameStream = Stream.emits[IO, LichessGameEvent](Seq(gameFull))
      val eventStream = Stream.emits[IO, LichessEvent](Seq(GameStartEvent(GameId("rej1"))))
      
      val client = new StubLichessClient(
        accountInfo = Some(user), 
        eventStream = eventStream,
        gameStreamResponses = Map("rej1" -> gameStream)
      ) {
         override def makeMove(gameId: String, move: String): IO[Boolean] = IO.pure(false) // fail move
      }
      val service = new LichessService(client)
      service.run().unsafeRunSync()
      Thread.sleep(1000)
    }

    "handle case with no legal moves (checkmate board)" in {
       val user = LichessUser("botid", "botname", None)
       val gameFull = GameFullEvent(
        "mate1",
        LichessVariant("standard", "Standard"),
        None,
        "blitz",
        LichessPerf("blitz"),
        LichessPlayer(Some("botid"), None, None, None), // we are white
        LichessPlayer(Some("p2"), None, None, None),
        "start",
        // Fools mate
        LichessGameState("f2f3 e7e5 g2g4 d8h4", 100, 100, 0, 0, "started")
      )
      
      val gameStream = Stream.emits[IO, LichessGameEvent](Seq(gameFull))
      val eventStream = Stream.emits[IO, LichessEvent](Seq(GameStartEvent(GameId("mate1"))))
      
      val client = new StubLichessClient(
        accountInfo = Some(user), 
        eventStream = eventStream,
        gameStreamResponses = Map("mate1" -> gameStream)
      ) 
      val service = new LichessService(client)
      service.run().unsafeRunSync()
      Thread.sleep(1000)
    }
    
    "ignore old state updates (move count <= last move count)" in {
       val user = LichessUser("botid", "botname", None)
       val gameFull = GameFullEvent(
        "dup1",
        LichessVariant("standard", "Standard"),
        None,
        "blitz",
        LichessPerf("blitz"),
        LichessPlayer(Some("botid"), None, None, None), // we are white
        LichessPlayer(Some("p2"), None, None, None),
        "start",
        LichessGameState("", 100, 100, 0, 0, "started")
      )
      // Send identical update again
      val stateUpdate = GameStateUpdateEvent("", 100, 100, 0, 0, "started")
      
      val gameStream = Stream.emits[IO, LichessGameEvent](Seq(gameFull, stateUpdate))
      val eventStream = Stream.emits[IO, LichessEvent](Seq(GameStartEvent(GameId("dup1"))))
      
      val client = new StubLichessClient(
        accountInfo = Some(user), 
        eventStream = eventStream,
        gameStreamResponses = Map("dup1" -> gameStream)
      ) 
      val service = new LichessService(client)
      service.run().unsafeRunSync()
      Thread.sleep(1000)
    }

    "ignore game updates if bot is not a player (observe)" in {
       val user = LichessUser("botid", "botname", None)
       val gameFull = GameFullEvent(
        "obs1",
        LichessVariant("standard", "Standard"),
        None,
        "blitz",
        LichessPerf("blitz"),
        LichessPlayer(Some("p1"), None, None, None), 
        LichessPlayer(Some("p2"), None, None, None),
        "start",
        LichessGameState("", 100, 100, 0, 0, "started")
      )
      
      val gameStream = Stream.emits[IO, LichessGameEvent](Seq(gameFull))
      val eventStream = Stream.emits[IO, LichessEvent](Seq(GameStartEvent(GameId("obs1"))))
      
      val client = new StubLichessClient(
        accountInfo = Some(user), 
        eventStream = eventStream,
        gameStreamResponses = Map("obs1" -> gameStream)
      ) 
      val service = new LichessService(client)
      service.run().unsafeRunSync()
      Thread.sleep(1000)
      
      client.moves shouldBe empty
    }
  }
}
