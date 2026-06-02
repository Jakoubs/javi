package chess.persistence.mongo

import cats.effect.IO
import com.mongodb.client.result.{DeleteResult, UpdateResult}
import com.mongodb.reactivestreams.client.{MongoClient, MongoClients, MongoCollection, MongoDatabase}
import com.mongodb.client.model.{Filters, Sorts, ReplaceOptions}
import org.bson.Document
import org.reactivestreams.{Publisher, Subscriber, Subscription}

import chess.persistence.dao.{GameDao, MoveEventDao, OpeningDao, PuzzleDao}
import chess.persistence.model.{MoveEvent, PersistedGame, Opening, Puzzle, PuzzleTheme}

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
  private val client:     MongoClient,
  private val gamesCol:   MongoCollection[PersistedGame],
  private val movesCol:   MongoCollection[MoveEvent],
  private val openCol:    MongoCollection[Opening],
  private val puzzleCol:  MongoCollection[Puzzle],
  private val themeCol:   MongoCollection[PuzzleTheme]
) extends GameDao with MoveEventDao with OpeningDao with PuzzleDao:

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

  // ─── OpeningDao ───────────────────────────────────────────────────────────

  override def findByFen(fen: String): IO[List[Opening]] =
    publisherToIO(openCol.find(Filters.eq("fen", fen)))

  override def save(opening: Opening): IO[Unit] =
    val filter = Filters.and(Filters.eq("fen", opening.fen), Filters.eq("move", opening.move))
    val opts   = new ReplaceOptions().upsert(true)
    publisherToUnit(openCol.replaceOne(filter, opening, opts))

  override def count(): IO[Long] =
    publisherToIO(openCol.countDocuments()).map(_.headOption.map(_.toLong).getOrElse(0L))

  override def deleteAll(): IO[Unit] =
    publisherToUnit(openCol.deleteMany(new Document()))

  /** Close the underlying MongoClient. */
  def close(): IO[Unit] = IO(client.close())

  def gameDao:      GameDao      = this
  def moveEventDao: MoveEventDao = this
  def openingDao:   OpeningDao   = this
  def puzzleDao:    PuzzleDao    = this

  // ─── PuzzleDao ─────────────────────────────────────────────────────────────

  override def save(puzzle: Puzzle): IO[Unit] =
    val filter = Filters.eq("_id", puzzle.id)
    val opts   = new ReplaceOptions().upsert(true)
    publisherToUnit(puzzleCol.replaceOne(filter, puzzle, opts))

  override def saveBatch(puzzles: List[Puzzle]): IO[Unit] =
    import cats.implicits.*
    puzzles.traverse(save).void

  override def getRandom(theme: Option[String]): IO[Option[Puzzle]] =
    import java.util
    val pipeline = new util.ArrayList[Document]()
    theme.foreach { t =>
      pipeline.add(new Document("$match", new Document("themes", t)))
    }
    pipeline.add(new Document("$sample", new Document("size", 1)))
    publisherToIO(puzzleCol.aggregate(pipeline)).map(_.headOption)

  override def findByTheme(theme: String, desc: Boolean, limit: Int, offset: Int): IO[List[Puzzle]] =
    val filter = Filters.eq("themes", theme)
    val sort   = if desc then Sorts.descending("rating") else Sorts.ascending("rating")
    publisherToIO(puzzleCol.find(filter).sort(sort).skip(offset).limit(limit))

  override def countPuzzles(): IO[Long] =
    publisherToIO(puzzleCol.countDocuments()).map(_.headOption.map(_.toLong).getOrElse(0L))

  override def saveTheme(theme: PuzzleTheme): IO[Unit] =
    val filter = Filters.eq("_id", theme.key)
    val opts   = new ReplaceOptions().upsert(true)
    publisherToUnit(themeCol.replaceOne(filter, theme, opts))

  override def findAllThemes(): IO[List[PuzzleTheme]] =
    publisherToIO(themeCol.find().sort(Sorts.ascending("name")))

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
      val openCol = db.getCollection(classOf[Opening].getName, classOf[Opening])
        .withCodecRegistry(MongoCodecs.registry)
      val puzzleCol = db.getCollection("puzzles", classOf[Puzzle])
        .withCodecRegistry(MongoCodecs.registry)
      val themeCol = db.getCollection("puzzle_themes", classOf[PuzzleTheme])
        .withCodecRegistry(MongoCodecs.registry)
      new MongoPersistence(client, gamesCol, movesCol, openCol, puzzleCol, themeCol)
    }
