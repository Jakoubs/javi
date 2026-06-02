package chess.rest

import cats.effect.*
import fs2.kafka.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import chess.model.Move

case class MoveEvent(
  sessionId: String,
  move: String,
  fenAfter: String,
  timestamp: Long = System.currentTimeMillis()
)

class KafkaService(producer: KafkaProducer[IO, String, String]):
  def publishMove(sessionId: String, move: Move, fen: String): IO[Unit] =
    val event = MoveEvent(sessionId, move.toInputString, fen)
    val record = ProducerRecord("chess-moves", sessionId, event.asJson.noSpaces)
    producer.produce(ProducerRecords.one(record)).flatten.void

object KafkaService:
  def make(bootstrapServer: String): Resource[IO, KafkaService] =
    val settings = ProducerSettings[IO, String, String]
      .withBootstrapServers(bootstrapServer)
    
    KafkaProducer.resource(settings).map(new KafkaService(_))
