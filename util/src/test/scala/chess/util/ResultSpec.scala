package chess.util

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import scala.util.{Try, Failure => TryFailure, Success => TrySuccess}

class ResultSpec extends AnyFunSpec with Matchers {

  describe("Result.apply / Success") {
    it("should create a Success wrapping the value") {
      val r = Result(42)
      r.isSuccess shouldBe true
      r.isFailure shouldBe false
    }

    it("should map over a Success value") {
      Result(10).map(_ * 2) shouldBe Success(20)
    }

    it("should flatMap over a Success value") {
      Result(10).flatMap(n => Result(n + 5)) shouldBe Success(15)
    }

    it("should return the value from getOrElse") {
      Result("hello").getOrElse("default") shouldBe "hello"
    }

    it("should convert Success to Right") {
      Result(1).toEither shouldBe Right(1)
    }

    it("should convert Success to Some") {
      Result(1).toOption shouldBe Some(1)
    }
  }

  describe("Result.fail / Failure") {
    it("should create a Failure with a message") {
      val r = Result.fail("error!")
      r.isSuccess shouldBe false
      r.isFailure shouldBe true
    }

    it("should propagate Failure through map") {
      Result.fail("oops").map(_ => 42) shouldBe Failure("oops")
    }

    it("should propagate Failure through flatMap") {
      Result.fail("oops").flatMap(_ => Result(99)) shouldBe Failure("oops")
    }

    it("should return default from getOrElse") {
      Result.fail("oops").getOrElse(0) shouldBe 0
    }

    it("should convert Failure to Left") {
      Result.fail("bad").toEither shouldBe Left("bad")
    }

    it("should convert Failure to None") {
      Result.fail("bad").toOption shouldBe None
    }
  }

  describe("Result.fromOption") {
    it("should return Success for Some") {
      Result.fromOption(Some("a"), "missing") shouldBe Success("a")
    }

    it("should return Failure for None") {
      Result.fromOption(None, "missing") shouldBe Failure("missing")
    }
  }

  describe("Result.fromEither") {
    it("should return Success for Right") {
      Result.fromEither(Right(42)) shouldBe Success(42)
    }

    it("should return Failure for Left") {
      Result.fromEither(Left("err")) shouldBe Failure("err")
    }
  }

  describe("Result.fromTry") {
    it("should return Success for TrySuccess") {
      Result.fromTry(TrySuccess(99)) shouldBe Success(99)
    }

    it("should return Failure for TryFailure") {
      val ex = new RuntimeException("boom")
      Result.fromTry(TryFailure(ex)) shouldBe Failure("boom")
    }
  }

  describe("Result.cond") {
    it("should return Success when condition is true") {
      Result.cond(true, "yes", "no") shouldBe Success("yes")
    }

    it("should return Failure when condition is false") {
      Result.cond(false, "yes", "no") shouldBe Failure("no")
    }
  }

  describe("Chaining") {
    it("should chain map and flatMap on Success") {
      val result = Result(5)
        .map(_ * 2)
        .flatMap(n => Result.cond(n > 5, n, "too small"))
        .map(_.toString)
      result shouldBe Success("10")
    }

    it("should short-circuit on a Failure in a chain") {
      val result: Result[Int] = Result(5)
        .flatMap(_ => Result.fail("stopped"))
      result shouldBe Failure("stopped")
    }
  }
}
