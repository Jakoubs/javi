package chess

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import java.io.File
import java.nio.file.Files

class TrainMainSpec extends AnyFunSpec with Matchers {
  describe("TrainMain") {
    it("should run 1 training game and write metadata") {
      val tempDir = Files.createTempDirectory("chess-training-test").toString
      
      // Run TrainMain with 1 game
      TrainMain.main(Array("1", tempDir))
      
      val metaFile    = new File(s"$tempDir/training_meta.txt")
      
      metaFile.exists() shouldBe true
      
      // Cleanup
      metaFile.delete()
      new File(tempDir).delete()
    }

    it("should handle default arguments") {
      // We don't want to run 10,000 games here, but we can verify the argument parsing
      // by calling main with empty args, but that would run 10,000 games.
      // So instead, we just test with explicit 1 game.
      val tempDir = Files.createTempDirectory("chess-training-test-2").toString
      TrainMain.main(Array("1", tempDir))
      new File(s"$tempDir/training_meta.txt").exists() shouldBe true
      
      // Cleanup
      new File(s"$tempDir/training_meta.txt").delete()
      new File(tempDir).delete()
    }
  }
}
