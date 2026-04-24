package chess.persistence.mongo

import cats.effect.IO
import com.mongodb.client.result.{DeleteResult, UpdateResult}
import com.mongodb.reactivestreams.client.{MongoClient, MongoClients, MongoCollection, MongoDatabase}
import com.mongodb.client.model.{Filters, Sorts, ReplaceOptions}
import org.bson.Document
import org.reactivestreams.{Publisher, Subscriber, Subscription}

import chess.persistence.dao.{GameDao, MoveEventDao}
import chess.persistence.model.{MoveEvent, PersistedGame}

import java.util.concurrent.CompletableFuture

/**
 * MongoDB Reactive Streams implementation of both [[GameDao]] and [[MoveEventDao]].
 *
 * The MongoDB driver returns `Publisher[T]` (Reactive Streams).
 * This implementation bridges them to `cats.effect.IO` via [[publisherToIO]]
 * so callers see the same unified effect type as the Slick implementation.
 *
 * Usage:
 * {{{
 *   MongoPersistence.make("mongodb://localhost:27017", "chess").flatMap { mongo =>
 *     mongo.gameDao.save(game) *> mongo.moveEventDao.save(event)
 *   }
 * }}}
 */
final class MongoPersistence private (
  private val client:    MongoClient,
  private val gamesCol:  MongoCollection[PersistedGame],
  private val movesCol:  MongoCollection[MoveEvent]
) extends GameDao with MoveEventDao:

  // ─── Reactive-Streams → IO bridge ─────────────────────────────────────────

  /** Collect all elements emitted by a `Publisher[A]` into `IO[List[A]]`. */
  private def publisherToIO[A](pub: Publisher[A]): IO[List[A]] =
    IO.fromCompletableFuture(IO {
      val cf  = new CompletableFuture[List[A]]()
      val buf = scala.collection.mutable.ListBuffer.empty[A]
      pub.subscribe(new Subscriber[A]:
        override def onSubscribe(s: Subscription): Unit = s.request(Long.MaxValue)
        override def onNext(t: A): Unit                 = buf += t
        override def onError(t: Throwable): Unit        = cf.completeExceptionally(t)
        override def onComplete(): Unit                  = cf.complete(buf.toList)
      )
      cf
    })

  private def publisherToUnit[A](pub: Publisher[A]): IO[Unit] =
    publisherToIO(pub).void

  // ─── GameDao ──────────────────────────────────────────────────────────────

  override def save(game: PersistedGame): IO[Unit] =
    publisherToUnit(gamesCol.insertOne(game))

  override def findById(id: String): IO[Option[PersistedGame]] =
    publisherToIO(gamesCol.find(Filters.eq("_id", id))).map(_.headOption)

  override def findAll(): IO[List[PersistedGame]] =
    publisherToIO(gamesCol.find().sort(Sorts.descending("createdAt")))

  override def update(game: PersistedGame): IO[Unit] =
    val filter  = Filters.eq("_id", game.id)
    val opts    = new ReplaceOptions().upsert(false)
    publisherToUnit(gamesCol.replaceOne(filter, game, opts))

  override def delete(id: String): IO[Unit] =
    val deleteMoves = movesCol.deleteMany(Filters.eq("gameId", id))
    val deleteGame  = gamesCol.deleteOne(Filters.eq("_id", id))
    publisherToUnit(deleteMoves) *> publisherToUnit(deleteGame)

  // ─── MoveEventDao ─────────────────────────────────────────────────────────

  override def save(event: MoveEvent): IO[Unit] =
    publisherToUnit(movesCol.insertOne(event))

  override def findByGameId(gameId: String): IO[List[MoveEvent]] =
    publisherToIO(
      movesCol.find(Filters.eq("gameId", gameId)).sort(Sorts.ascending("moveNumber"))
    )

  override def deleteByGameId(gameId: String): IO[Unit] =
    publisherToUnit(movesCol.deleteMany(Filters.eq("gameId", gameId)))

  /** Close the underlying MongoClient. */
  def close(): IO[Unit] = IO(client.close())

  def gameDao:      GameDao      = this
  def moveEventDao: MoveEventDao = this

object MongoPersistence:

  /**
   * Connect to MongoDB and return a ready-to-use [[MongoPersistence]].
   *
   * Collections are created lazily by MongoDB on first write.
   */
  def make(uri: String, databaseName: String): IO[MongoPersistence] =
    IO {
      val client = MongoClients.create(uri)
      val db     = client
        .getDatabase(databaseName)
        .withCodecRegistry(MongoCodecs.registry)
      val gamesCol = db.getCollection(classOf[PersistedGame].getName, classOf[PersistedGame])
        .withCodecRegistry(MongoCodecs.registry)
      val movesCol = db.getCollection(classOf[MoveEvent].getName, classOf[MoveEvent])
        .withCodecRegistry(MongoCodecs.registry)
      new MongoPersistence(client, gamesCol, movesCol)
    }
