package chess.model

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/**
 * Gezielte Tests für TimeControl (bisher 0% Statement-Coverage):
 *   - TimeControl.toCommand mit initialMillis = Some(...)
 *   - TimeControl.toCommand mit initialMillis = None
 *   - TimeControl.presets Liste (Werte und Länge)
 */
class TimeControlSpec extends AnyFunSpec with Matchers {

  describe("TimeControl") {

    describe("toCommand") {
      it("should produce 'start <millis> <increment>' when initialMillis is defined") {
        val tc = TimeControl("5|3 Blitz", Some(5 * 60 * 1000L), 3 * 1000L)
        tc.toCommand shouldBe "start 300000 3000"
      }

      it("should produce 'start none' when initialMillis is None") {
        val tc = TimeControl("Unlimited", None, 0L)
        tc.toCommand shouldBe "start none"
      }

      it("should produce 'start 0 0' for a zero-time control") {
        val tc = TimeControl("Zero", Some(0L), 0L)
        tc.toCommand shouldBe "start 0 0"
      }
    }

    describe("TimeControl.presets") {
      it("should have exactly 4 presets") {
        TimeControl.presets should have size 4
      }

      it("should contain 'Unlimited' preset with no initial time") {
        val unlimited = TimeControl.presets.find(_.name == "Unlimited")
        unlimited should not be empty
        unlimited.get.initialMillis shouldBe None
        unlimited.get.toCommand shouldBe "start none"
      }

      it("should contain '1|0 Bullet' preset with 1 minute and no increment") {
        val bullet = TimeControl.presets.find(_.name == "1|0 Bullet")
        bullet should not be empty
        bullet.get.initialMillis shouldBe Some(60000L)
        bullet.get.incrementMillis shouldBe 0L
        bullet.get.toCommand shouldBe "start 60000 0"
      }

      it("should contain '3|2 Blitz' preset with 3 minutes and 2s increment") {
        val blitz = TimeControl.presets.find(_.name == "3|2 Blitz")
        blitz should not be empty
        blitz.get.initialMillis shouldBe Some(180000L)
        blitz.get.incrementMillis shouldBe 2000L
        blitz.get.toCommand shouldBe "start 180000 2000"
      }

      it("should contain '10|0 Rapid' preset with 10 minutes and no increment") {
        val rapid = TimeControl.presets.find(_.name == "10|0 Rapid")
        rapid should not be empty
        rapid.get.initialMillis shouldBe Some(600000L)
        rapid.get.incrementMillis shouldBe 0L
        rapid.get.toCommand shouldBe "start 600000 0"
      }
    }
  }
}
