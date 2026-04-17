package chess.lichess

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Paths}

class LichessMainSpec extends AnyWordSpec with Matchers {

  "LichessMain" should {
    
    "run successfully with a valid dummy token" in {
       val tokenPath = Paths.get("lichess.token")
       var existed = false
       var tempContent = Array.emptyByteArray
       if (Files.exists(tokenPath)) {
         existed = true
         tempContent = Files.readAllBytes(tokenPath)
       }
       
       Files.write(tokenPath, "dummy_token".getBytes)
       
       try {
         // Should try to run, fail at getAccountInfo (since dummy token is invalid on real Lichess), 
         // print an error message, and complete the IO naturally.
         LichessMain.run.unsafeRunSync()
       } finally {
         if (existed) Files.write(tokenPath, tempContent)
         else Files.delete(tokenPath)
       }
    }
    
    "handle exception if token is missing" in {
       val tokenPath = Paths.get("lichess.token")
       var existed = false
       var tempContent = Array.emptyByteArray
       if (Files.exists(tokenPath)) {
         existed = true
         tempContent = Files.readAllBytes(tokenPath)
         Files.delete(tokenPath)
       }
       
       try {
         // Program handles error with handleErrorWith
         LichessMain.run.unsafeRunSync()
       } finally {
         if (existed) Files.write(tokenPath, tempContent)
       }
    }
  }
}
