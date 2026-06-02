package chess.lichess

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.ExecutionContext

object LichessMain {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "LichessBotSystem")
    implicit val ec: ExecutionContext = system.executionContext

    try {
      val token = LichessClient.loadToken()
      val client = new LichessClient(token)
      val service = new LichessService(client)

      println("Javi Chess Bot - Lichess Integration")
      println("-------------------------------------")
      
      service.start()

      // Optional: Herausforderung gegen einen bestimmten Bot starten
      if (args.nonEmpty) {
        val targetBot = args(0)
        println(s"Sende Herausforderung an $targetBot...")
        client.challengeBot(targetBot).onComplete {
          case scala.util.Success(true) => println(s"Herausforderung an $targetBot gesendet.")
          case scala.util.Success(false) => println(s"Herausforderung an $targetBot fehlgeschlagen.")
          case scala.util.Failure(e) => println(s"Fehler beim Fordern: ${e.getMessage}")
        }
      }

      // Keep application running
      Thread.currentThread().join()
    } catch {
      case e: Exception =>
        println(s"Kritischer Fehler beim Starten: ${e.getMessage}")
        system.terminate()
        sys.exit(1)
    }
  }
}
