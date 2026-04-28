package chess.persistence

import chess.persistence.config.PersistenceConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class PersistenceConfigSpec extends AnyFunSpec with Matchers {
  describe("PersistenceConfig") {
    it("should read slick and mongo settings from a custom config") {
      val cfg = ConfigFactory.parseString(
        """
          |chess.persistence {
          |  backend = "mongo"
          |  slick {
          |    url = "jdbc:test"
          |    user = "sa"
          |    password = "pw"
          |    driver = "org.h2.Driver"
          |    pool-size = 4
          |  }
          |  mongo {
          |    uri = "mongodb://localhost:27017"
          |    database = "chess_test"
          |  }
          |}
          |""".stripMargin
      )

      val parsed = PersistenceConfig.from(cfg)
      parsed.backend shouldBe "mongo"
      parsed.slick.url shouldBe "jdbc:test"
      parsed.slick.user shouldBe "sa"
      parsed.slick.password shouldBe "pw"
      parsed.slick.driver shouldBe "org.h2.Driver"
      parsed.slick.poolSize shouldBe 4
      parsed.mongo.uri shouldBe "mongodb://localhost:27017"
      parsed.mongo.database shouldBe "chess_test"
    }
  }
}
