package chess.util

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class ObserverSpec extends AnyFunSpec with Matchers {

  // A simple concrete Observable for testing
  class TestBus extends Observable[String]

  describe("Observable / Observer") {
    it("should notify a registered observer") {
      val bus = new TestBus
      var received: Option[String] = None

      val obs = new Observer[String]:
        def update(state: String): Unit = received = Some(state)

      bus.addObserver(obs)
      bus.notifyObservers("hello")

      received shouldBe Some("hello")
    }

    it("should notify multiple observers") {
      val bus = new TestBus
      val log = collection.mutable.Buffer[String]()

      val obs1 = new Observer[String]:
        def update(state: String): Unit = log += s"obs1:$state"
      val obs2 = new Observer[String]:
        def update(state: String): Unit = log += s"obs2:$state"

      bus.addObserver(obs1)
      bus.addObserver(obs2)
      bus.notifyObservers("event")

      log should contain("obs1:event")
      log should contain("obs2:event")
    }

    it("should not notify removed observers") {
      val bus = new TestBus
      var notified = false

      val obs = new Observer[String]:
        def update(state: String): Unit = notified = true

      bus.addObserver(obs)
      bus.removeObserver(obs)
      bus.notifyObservers("ignored")

      notified shouldBe false
    }

    it("should do nothing when notifying with no observers") {
      val bus = new TestBus
      noException should be thrownBy bus.notifyObservers("nobody home")
    }

    it("should notify multiple times and accumulate updates") {
      val bus = new TestBus
      val events = collection.mutable.Buffer[String]()

      val obs = new Observer[String]:
        def update(state: String): Unit = events += state

      bus.addObserver(obs)
      bus.notifyObservers("first")
      bus.notifyObservers("second")
      bus.notifyObservers("third")

      events.toList shouldBe List("first", "second", "third")
    }

    it("should allow adding the same observer twice (fires twice)") {
      val bus = new TestBus
      var count = 0

      val obs = new Observer[String]:
        def update(state: String): Unit = count += 1

      bus.addObserver(obs)
      bus.addObserver(obs) // added twice
      bus.notifyObservers("x")

      count shouldBe 2
    }

    it("should only remove one instance when observer added twice") {
      val bus = new TestBus
      var count = 0

      val obs = new Observer[String]:
        def update(state: String): Unit = count += 1

      bus.addObserver(obs)
      bus.addObserver(obs)
      bus.removeObserver(obs) // removes first match only
      bus.notifyObservers("x")

      count shouldBe 1
    }
  }
}
