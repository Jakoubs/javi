package chess.view

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{CompletableFuture, ExecutorService, Executors}
import scala.concurrent.{Future, ExecutionContext}
import scala.jdk.FutureConverters.*
import io.circe.parser.decode
import io.circe.syntax.*
import chess.controller.{CommandRequest, GameStateResponse}

class JaviClient(baseUrl: String = "http://localhost:8080"):
  private val ec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private val httpClient = HttpClient.newBuilder().executor(ec).build()
  private val sessionId = java.util.UUID.randomUUID().toString()

  def fetchState(): Future[Either[String, GameStateResponse]] =
    val request = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/api/state?sessionId=$sessionId"))
      .GET()
      .build()
      
    val javaFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    javaFuture.asScala.map { response =>
      if (response.statusCode() == 200) {
        decode[GameStateResponse](response.body()).left.map(_.getMessage)
      } else {
        Left(s"Server error: ${response.statusCode()}")
      }
    }(ec)

  def sendCommand(cmd: String): Future[Either[String, String]] =
    val jsonBody = CommandRequest(cmd).asJson.noSpaces
    val request = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/api/command?sessionId=$sessionId"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
      .build()

    val javaFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    javaFuture.asScala.map { response =>
      if (response.statusCode() == 200 || response.statusCode() == 201) Right("OK")
      else Left(s"Error: ${response.statusCode()}")
    }(ec)

object JaviClient:
  def apply(baseUrl: String = "http://localhost:8080") =
    new JaviClient(baseUrl)
