package chess.util

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/**
 * Deckt die bisher fehlende Branch in Observable.removeObserver ab:
 *   if index != -1  →  currently the `else` (index == -1) branch is not hit.
 */
class ObserverCoverageSpec extends AnyFunSpec with Matchers {

  class TestBus extends Observable[Int]

  describe("Observable.removeObserver – missing branch") {
    it("should do nothing when removing an observer that was never added (index == -1)") {
      val bus = new TestBus
      var count = 0

      val registered = new Observer[Int]:
        def update(state: Int): Unit = count += 1

      val notRegistered = new Observer[Int]:
        def update(state: Int): Unit = count += 100

      bus.addObserver(registered)

      // Removing an observer that was never added should be a no-op
      noException should be thrownBy bus.removeObserver(notRegistered)

      // registered observer should still receive events
      bus.notifyObservers(1)
      count shouldBe 1
    }

    it("should not remove anything when bus is empty and removeObserver is called") {
      val bus = new TestBus
      val obs = new Observer[Int]:
        def update(state: Int): Unit = ()

      noException should be thrownBy bus.removeObserver(obs)
      // Nothing to notify, no crash
      noException should be thrownBy bus.notifyObservers(42)
    }
  }
}
