package chess

import cats.effect.{IO, IOApp}
import cats.effect.unsafe.implicits.global
import chess.controller.GameSession
import chess.view.{GuiApp, TuiRunner}
import chess.web.WebMain

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object UnifiedMain extends IOApp.Simple:

  def run: IO[Unit] =
    println("Starting Chess with TUI + GUI + WebUI...")
    val session = new GameSession()

    // Start WebUI server in background
    val webServerThread = new Thread(() => {
      println("Starting WebUI server...")
      try
        WebMain.runWithSession(session).unsafeRunSync()
      catch
        case e: Exception =>
          println(s"Web server error: ${e.getMessage}")
          e.printStackTrace()
    })
    webServerThread.setName("WebUI-Server")
    webServerThread.setDaemon(false) // Changed to non-daemon to keep JVM alive
    webServerThread.start()

    // Give WebUI time to start
    Thread.sleep(2000)

    // Start GUI on separate thread
    val guiThread = new Thread(() => {
      println("Starting GUI...")
      try
        GuiApp.start(session)
        println("GUI started successfully")
        // Keep GUI thread alive
        while true do 
          Thread.sleep(1000)
      catch
        case e: Exception =>
          println(s"GUI error: ${e.getMessage}")
          e.printStackTrace()
    })
    guiThread.setName("GUI-Thread")
    guiThread.setDaemon(false) // Changed to non-daemon to keep JVM alive
    guiThread.start()

    // Give GUI time to start
    Thread.sleep(1000)

    println("All UIs started:")
    println("- TUI: Running in this terminal")
    println("- GUI: Desktop window should open")
    println("- WebUI: Available at http://localhost:8080")
    println()

    // Run TUI on main thread (blocking)
    IO.delay {
      try
        TuiRunner.run(session)
      catch
        case _: InterruptedException =>
          println("TUI interrupted, shutting down...")
    }
