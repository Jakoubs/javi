package chess

import chess.controller.GameController
import chess.view.{Tui, Gui}
import chess.rest.Http4sRestApi
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s.*

object Main:
  private var activeRestApi: Option[cats.effect.FiberIO[Nothing]] = None

  def main(args: Array[String]): Unit =
    setup(args)
    // Start ScalaFX GUI (takes over the main thread)
    Gui.main(args)

  def setup(args: Array[String]): Unit =
    val tui = new Tui()
    GameController.addObserver(tui)
    
    val restApi = new Http4sRestApi()
    GameController.addObserver(restApi)
    
    val serverConfig = EmberServerBuilder.default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(restApi.app)
      .build

    // Start server in background using cats-effect Fiber
    activeRestApi = Some(serverConfig.useForever.start.unsafeRunSync())
    println("[REST API] Server online at http://localhost:8080/")

    // TUI blocks on readLine, so we push it to a background thread
    Future { tui.run() }

  def shutdown(): Unit =
    activeRestApi.foreach(_.cancel.unsafeRunSync())
    activeRestApi = None

