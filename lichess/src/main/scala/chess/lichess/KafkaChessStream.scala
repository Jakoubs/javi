package chess.lichess

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.kafka.scaladsl.{Consumer, Producer}
import org.apache.pekko.kafka.{CommitterSettings, ConsumerSettings, ProducerSettings, Subscriptions}
import org.apache.pekko.stream.scaladsl.*
import org.apache.pekko.Done
import org.apache.pekko.stream.{KillSwitches, UniqueKillSwitch}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.*
import chess.ai.Evaluator
import chess.model.*

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

// ─── Kafka message types ──────────────────────────────────────────────────────

/**
 * DTO for the "chess-moves" topic.
 * Published by the REST service (KafkaService) every time a player makes a move.
 * Consumed here by the Pekko Stream consumer pipeline.
 */
case class MoveEvent(
  sessionId: String,
  move:      String,
  fenAfter:  String,
  timestamp: Long = System.currentTimeMillis()
)

/**
 * DTO for the "chess-game-reports" topic.
 * Published by our Pekko Stream producer after completing N self-play games.
 * GameStatus is serialised as a plain String to keep the JSON simple.
 */
case class GameReportMsg(
  gameId:       Int,
  totalMoves:   Int,
  result:       String,
  maxEval:      Double,
  minEval:      Double,
  avgLegal:     Double,
  peakMobility: Int
)

object GameReportMsg:
  def from(r: GameReport): GameReportMsg =
    GameReportMsg(
      gameId       = r.gameId,
      totalMoves   = r.totalMoves,
      result       = ChessGameStream.resultLabel(r.result),
      maxEval      = r.maxEval,
      minEval      = r.minEval,
      avgLegal     = r.avgLegal,
      peakMobility = r.peakMobility
    )

// ─── Kafka stream pipeline ────────────────────────────────────────────────────

object KafkaChessStream:

  val TOPIC_GAME_REPORTS = "chess-game-reports"
  val TOPIC_MOVES        = "chess-moves"

  // ── Shared settings builders ───────────────────────────────────────────────

  private def producerSettings(bootstrapServers: String)(using system: ActorSystem[?]) =
    ProducerSettings(system, new StringSerializer, new StringSerializer)
      .withBootstrapServers(bootstrapServers)

  private def consumerSettings(bootstrapServers: String, groupId: String)(using system: ActorSystem[?]) =
    ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(groupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")

  // ── PRODUCER: Pekko Stream → Kafka ────────────────────────────────────────
  //
  // Wires the existing ChessGameStream analysis pipeline to a Kafka Sink
  // instead of Sink.seq.  Each completed GameReport is serialised as JSON and
  // published to the "chess-game-reports" topic.
  //
  // Pipeline shape:
  //
  //   Source[List[PositionSnapshot]]   (random self-play games)
  //     ──▶ Flow[PositionSnapshot]     (flatten)
  //     ──▶ Flow[GameReport]           (aggregate per game)
  //     ──▶ Flow[ProducerRecord]       (serialise to JSON)
  //     ──▶ Producer.plainSink         (publish to Kafka)

  def runProducer(
    numGames:         Int    = 5,
    bootstrapServers: String = "localhost:9092"
  )(using system: ActorSystem[?]): Future[Done] =
    given ExecutionContext = system.executionContext

    println(s"\n[PRODUCER] Starting — will publish $numGames game reports to '$TOPIC_GAME_REPORTS'")

    ChessGameStream.gameSource(numGames)                    // Source[List[PositionSnapshot]]
      .via(ChessGameStream.analysisFlow)                    // Flow → PositionSnapshot
      .wireTap { snap =>
        val advantage = if snap.evalScore > 0 then s"White +${snap.evalScore.toInt}" else s"Black +${math.abs(snap.evalScore.toInt)}"
        println(f"[PRODUCER-EVAL] game=${snap.gameId} move=${snap.moveNumber}%-3d eval=$advantage%-16s fen=${snap.state.toFen.take(25)}...")
      }
      .via(ChessGameStream.aggregationFlow(numGames))       // Flow → GameReport
      .map { report =>
        val msg  = GameReportMsg.from(report)
        val json = msg.asJson.noSpaces
        val key  = s"game-${report.gameId}"
        println(s"[PRODUCER] → topic=$TOPIC_GAME_REPORTS  key=$key  result=${msg.result}  moves=${msg.totalMoves}")
        new ProducerRecord[String, String](TOPIC_GAME_REPORTS, key, json)
      }
      .runWith(Producer.plainSink(producerSettings(bootstrapServers)))
      .andThen {
        case Success(_) => println(s"[PRODUCER] Done — $numGames reports published to Kafka ✓")
        case Failure(e) => println(s"[PRODUCER] Error: ${e.getMessage}")
      }

  // ── CONSUMER: Kafka → Pekko Stream ────────────────────────────────────────
  //
  // Reads MoveEvent JSON messages from the "chess-moves" topic (written by the
  // REST service every time a player makes a move) and runs each position
  // through the Evaluator — all as a live Pekko reactive stream.
  //
  // Pipeline shape:
  //
  //   Consumer.plainSource          (Kafka "chess-moves" topic)
  //     ──▶ Flow[MoveEvent]         (deserialise JSON)
  //     ──▶ Flow[(event, eval)]     (evaluate position via Evaluator)
  //     ──▶ Sink.foreach            (log analysis result)

  def runConsumer(
    bootstrapServers: String = "localhost:9092",
    groupId:          String = "chess-analysis-group"
  )(using system: ActorSystem[?]): (UniqueKillSwitch, Future[Done]) =
    given ExecutionContext = system.executionContext

    println(s"\n[CONSUMER] Starting — reading from '$TOPIC_MOVES' (group=$groupId)")

    val (killSwitch, doneFuture) =
      Consumer
        .plainSource(
          consumerSettings(bootstrapServers, groupId),
          Subscriptions.topics(TOPIC_MOVES)
        )

        // ── Deserialise JSON → MoveEvent ──────────────────────────────────
        .map(record => (record.key(), record.value()))
        .map { case (key, json) =>
          decode[MoveEvent](json) match
            case Right(event) => Some(event)
            case Left(err)    =>
              println(s"[CONSUMER] JSON parse error (key=$key): $err")
              None
        }
        .collect { case Some(event) => event }   // drop failed parses

        // ── Position evaluation flow ──────────────────────────────────────
        .mapAsync(4) { event =>
          Future {
            val evalScore: Double =
              GameState.fromFen(event.fenAfter) match
                case Right(state) => Evaluator.evaluate(state)
                case Left(err)    =>
                  println(s"[CONSUMER] FEN parse error for ${event.fenAfter.take(20)}: $err")
                  0.0

            (event, evalScore)
          }(scala.concurrent.ExecutionContext.Implicits.global)
        }

        // ── Sink: log the analysis result ─────────────────────────────────
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.foreach { case (event, eval) =>
          val advantage = if eval > 0 then s"White +${eval.toInt}" else s"Black +${math.abs(eval.toInt)}"
          println(
            f"[CONSUMER] session=${event.sessionId}  move=${event.move}%-8s  " +
            f"eval=$advantage%-16s  fen=${event.fenAfter.take(25)}..."
          )
        })(Keep.both)
        .run()

    doneFuture.onComplete {
      case Success(_) => println("[CONSUMER] Stream completed.")
      case Failure(e) => println(s"[CONSUMER] Stream error: ${e.getMessage}")
    }

    (killSwitch, doneFuture)

// ─── Standalone entry point ───────────────────────────────────────────────────

/**
 * Runs both the Kafka Producer and Consumer as Pekko Streams concurrently.
 *
 * Usage:
 *   sbt "lichess/runMain chess.lichess.KafkaStreamMain"             (default: 5 games, localhost:9092)
 *   sbt "lichess/runMain chess.lichess.KafkaStreamMain 10 kafka:9092"
 *
 * Requires a running Kafka broker.  Start with:
 *   docker compose up kafka
 */
object KafkaStreamMain:

  def main(args: Array[String]): Unit =
    val numGames   = args.lift(0).flatMap(_.toIntOption).getOrElse(5)
    val bootstrap  = args.lift(1).getOrElse("localhost:9092")

    given system: ActorSystem[Nothing] =
      ActorSystem(Behaviors.empty, "ChessKafkaStreamSystem")

    given ec: ExecutionContext = system.executionContext

    println("=" * 60)
    println("  Chess Kafka Stream")
    println(s"  Broker : $bootstrap")
    println(s"  Games  : $numGames")
    println("=" * 60)

    // Start consumer in background (runs until killed)
    val (consumerKillSwitch, consumerDone) =
      KafkaChessStream.runConsumer(bootstrap)

    // Run producer: plays N games and publishes reports; finishes after numGames
    val producerDone = KafkaChessStream.runProducer(numGames, bootstrap)

    // Wait for producer to finish, but leave the consumer running
    producerDone.onComplete { _ =>
      println("\n[MAIN] Producer done. Consumer will continue to listen and evaluate moves indefinitely...")
    }

    // Only terminate the actor system if the consumer dies or is manually stopped
    consumerDone.onComplete { _ =>
      system.terminate()
    }

    import scala.concurrent.duration.Duration
    Await.result(system.whenTerminated, Duration.Inf)
    println("\nKafka stream finished. Goodbye!")
