package chess

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import java.io.{File, PrintWriter}
import java.nio.file.Files

class TrainMainCoverageSpec extends AnyFunSpec with Matchers {

  // ── readTotalGames with an EXISTING meta file ───────────────────────────────

  describe("TrainMain.readTotalGames") {
    it("should read cumulative game count from an existing meta file") {
      val dir = Files.createTempDirectory("chess-tm-read").toString
      // Write a meta file manually
      val meta = new File(s"$dir/training_meta.txt")
      val pw = new PrintWriter(meta)
      pw.println("75000")
      pw.close()

      // Run 1 game – it will load previousTotal = 75000
      TrainMain.main(Array("1", dir))

      // After 1 game, total should be 75001
      val written = scala.io.Source.fromFile(s"$dir/training_meta.txt")
      val total = written.getLines().next().trim.toLong
      written.close()
      total shouldBe 75001L

      // Cleanup
      new File(s"$dir/ai_weights.txt").delete()
      meta.delete()
      new File(dir).delete()
    }

    it("should return 0L when meta file is missing") {
      val dir = Files.createTempDirectory("chess-tm-nometa").toString
      // No meta file written – readTotalGames should return 0
      TrainMain.main(Array("1", dir))

      val meta = new File(s"$dir/training_meta.txt")
      val written = scala.io.Source.fromFile(meta)
      val total = written.getLines().next().trim.toLong
      written.close()
      total shouldBe 1L

      new File(s"$dir/ai_weights.txt").delete()
      meta.delete()
      new File(dir).delete()
    }

    it("should return 0L when meta file exists but is corrupted") {
      val dir = Files.createTempDirectory("chess-tm-corrupt").toString
      val meta = new File(s"$dir/training_meta.txt")
      val pw = new PrintWriter(meta)
      pw.println("NOT_A_NUMBER")
      pw.close()

      // Should fall back to 0L via getOrElse
      TrainMain.main(Array("1", dir))

      // After 1 game with previousTotal=0 (fallback), final = 1
      val written = scala.io.Source.fromFile(s"$dir/training_meta.txt")
      val total = written.getLines().next().trim.toLong
      written.close()
      total shouldBe 1L

      new File(s"$dir/ai_weights.txt").delete()
      meta.delete()
      new File(dir).delete()
    }
  }

  // ── main: existing weights file loading path ────────────────────────────────

  describe("TrainMain.main with existing weights file") {
    it("should load existing weights when ai_weights.txt is present") {
      val dir = Files.createTempDirectory("chess-tm-weights").toString

      // First run – creates weights
      TrainMain.main(Array("1", dir))
      new File(s"$dir/ai_weights.txt").exists() shouldBe true

      // Second run – existing weights file should be loaded (no exception)
      noException should be thrownBy TrainMain.main(Array("1", dir))

      new File(s"$dir/ai_weights.txt").delete()
      new File(s"$dir/training_meta.txt").delete()
      new File(dir).delete()
    }
  }

  // ── simulateGame with custom MAX_MOVES_PER_GAME ─────────────────────────────

  describe("TrainMain.simulateGame (package-private)") {
    it("should terminate via max-moves limit when MAX_MOVES_PER_GAME is tiny") {
      val origMax = TrainMain.MAX_MOVES_PER_GAME
      try {
        // Force a very short game so the "max moves reached" path fires
        TrainMain.MAX_MOVES_PER_GAME = 2
        // Should not throw
        noException should be thrownBy TrainMain.simulateGame()
      } finally {
        TrainMain.MAX_MOVES_PER_GAME = origMax
      }
    }
  }

  // ── checkpoint boundary logic ────────────────────────────────────────────────

  describe("TrainMain checkpoint logic") {
    it("should save a versioned checkpoint when crossing the CHECKPOINT_INTERVAL") {
      val origInterval = TrainMain.CHECKPOINT_INTERVAL
      val origMax      = TrainMain.MAX_MOVES_PER_GAME
      try {
        // Set a tiny checkpoint interval so we cross it with just 2 games
        TrainMain.CHECKPOINT_INTERVAL = 1
        TrainMain.MAX_MOVES_PER_GAME  = 2

        val dir = Files.createTempDirectory("chess-tm-checkpoint").toString
        TrainMain.main(Array("2", dir))

        // At least one versioned file should exist
        val versioned = new File(dir).listFiles().filter(_.getName.startsWith("ai_weights_v"))
        versioned.length should be > 0

        // Cleanup
        new File(dir).listFiles().foreach(_.delete())
        new File(dir).delete()
      } finally {
        TrainMain.CHECKPOINT_INTERVAL = origInterval
        TrainMain.MAX_MOVES_PER_GAME  = origMax
      }
    }
  }
}
