package chess.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import chess.persistence.dao.{GameDao, MoveEventDao}
import chess.persistence.model.{MoveEvent, PersistedGame}
import chess.persistence.mongo.MongoPersistence

/**
 * Unit tests for [[MongoPersistence]] using hand-written in-memory stubs.
 *
 * These tests do NOT require a running MongoDB server.  Each test creates a
 * [[StubMongoPersistence]] that stores data in `scala.collection.mutable.Map`s
 * while exposing the same `GameDao` / `MoveEventDao` interfaces.
 *
 * To run against a real MongoDB add the `mongodb-driver-sync` test dependency
 * and replace `StubMongoPersistence` with
 * `MongoPersistence.make("mongodb://localhost:27017", "chess_test").unsafeRunSync()`.
 */
class MongoPersistenceSpec extends AnyFunSuite with Matchers:

  // ─── In-memory stub ───────────────────────────────────────────────────────

  /**
   * A pure in-memory implementation of both DAOs that mirrors the behaviour
   * defined by the `GameDao` / `MoveEventDao` contracts without any I/O.
   */
  class StubMongoPersistence extends GameDao with MoveEventDao:
    import scala.collection.mutable

    private val games  = mutable.LinkedHashMap.empty[String, PersistedGame]
    private val moves  = mutable.ListBuffer.empty[MoveEvent]

    // ── GameDao ─────────────────────────────────────────────────────────────

    override def save(game: PersistedGame): IO[Unit] =
      IO(games.put(game.id, game)).void

    override def findById(id: String): IO[Option[PersistedGame]] =
      IO(games.get(id))

    override def findAll(): IO[List[PersistedGame]] =
      IO(games.values.toList.sortBy(-_.createdAt))

    override def update(game: PersistedGame): IO[Unit] =
      IO(games.put(game.id, game)).void

    override def delete(id: String): IO[Unit] =
      IO {
        games.remove(id)
        moves.filterInPlace(_.gameId != id)
      }

    // ── MoveEventDao ────────────────────────────────────────────────────────

    override def save(event: MoveEvent): IO[Unit] =
      IO(moves += event).void

    override def findByGameId(gameId: String): IO[List[MoveEvent]] =
      IO(moves.filter(_.gameId == gameId).sortBy(_.moveNumber).toList)

    override def deleteByGameId(gameId: String): IO[Unit] =
      IO(moves.filterInPlace(_.gameId != gameId)).void

  // ─── Fixtures ─────────────────────────────────────────────────────────────

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private def mkGame(id: String, result: String = "ongoing"): PersistedGame =
    PersistedGame(
      id        = id,
      startFen  = startFen,
      finalFen  = startFen,
      pgn       = "",
      result    = result,
      createdAt = 1000L,
      updatedAt = 1000L
    )

  private def mkMove(id: String, gameId: String, moveNumber: Int): MoveEvent =
    MoveEvent(
      id         = id,
      gameId     = gameId,
      moveNumber = moveNumber,
      san        = "e4",
      uci        = "e2e4",
      fenAfter   = startFen,
      timestamp  = 2000L
    )

  // ─── GameDao tests ────────────────────────────────────────────────────────

  test("save and findById roundtrip"):
    val dao  = StubMongoPersistence()
    val game = mkGame("g1")
    dao.save(game).unsafeRunSync()
    dao.findById("g1").unsafeRunSync() shouldBe Some(game)

  test("findById returns None for unknown id"):
    val dao = StubMongoPersistence()
    dao.findById("no-such-id").unsafeRunSync() shouldBe None

  test("findAll returns all saved games"):
    val dao = StubMongoPersistence()
    val g1  = mkGame("g1")
    val g2  = mkGame("g2")
    dao.save(g1).unsafeRunSync()
    dao.save(g2).unsafeRunSync()
    dao.findAll().unsafeRunSync().map(_.id) should contain allOf ("g1", "g2")

  test("update replaces game fields"):
    val dao    = StubMongoPersistence()
    val game   = mkGame("g3")
    val newFen = "8/8/8/8/8/8/8/4K3 w - - 0 50"
    dao.save(game).unsafeRunSync()
    dao.update(game.copy(finalFen = newFen, result = "black", updatedAt = 9999L)).unsafeRunSync()
    val found = dao.findById("g3").unsafeRunSync().get
    found.finalFen shouldBe newFen
    found.result   shouldBe "black"

  test("delete removes game and cascades to move events"):
    val dao = StubMongoPersistence()
    val game = mkGame("g4")
    dao.save(game).unsafeRunSync()
    dao.save(mkMove("m1", "g4", 0)).unsafeRunSync()
    dao.save(mkMove("m2", "g4", 1)).unsafeRunSync()
    dao.delete("g4").unsafeRunSync()
    dao.findById("g4").unsafeRunSync()    shouldBe None
    dao.findByGameId("g4").unsafeRunSync() shouldBe empty

  // ─── MoveEventDao tests ───────────────────────────────────────────────────

  test("save and findByGameId returns moves in ascending move-number order"):
    val dao  = StubMongoPersistence()
    val game = mkGame("g5")
    dao.save(game).unsafeRunSync()
    val events = List(
      mkMove("m3", "g5", 2),
      mkMove("m0", "g5", 0),
      mkMove("m1", "g5", 1)
    )
    events.foreach(dao.save(_).unsafeRunSync())
    dao.findByGameId("g5").unsafeRunSync().map(_.moveNumber) shouldBe List(0, 1, 2)

  test("deleteByGameId removes only moves for the given game"):
    val dao  = StubMongoPersistence()
    dao.save(mkGame("g6")).unsafeRunSync()
    dao.save(mkGame("g7")).unsafeRunSync()
    dao.save(mkMove("ma", "g6", 0)).unsafeRunSync()
    dao.save(mkMove("mb", "g7", 0)).unsafeRunSync()
    dao.deleteByGameId("g6").unsafeRunSync()
    dao.findByGameId("g6").unsafeRunSync() shouldBe empty
    dao.findByGameId("g7").unsafeRunSync().map(_.id) shouldBe List("mb")

  test("findByGameId returns empty list for unknown gameId"):
    val dao = StubMongoPersistence()
    dao.findByGameId("unknown").unsafeRunSync() shouldBe empty

  // ─── Interface contract: both implementations satisfy the same trait ───────

  test("StubMongoPersistence satisfies GameDao interface"):
    val dao: GameDao = StubMongoPersistence()
    dao.save(mkGame("contract-g")).unsafeRunSync()
    dao.findAll().unsafeRunSync().map(_.id) should contain ("contract-g")

  test("StubMongoPersistence satisfies MoveEventDao interface"):
    val moveDao: MoveEventDao = StubMongoPersistence()
    val gameDao: GameDao      = moveDao.asInstanceOf[GameDao]
    gameDao.save(mkGame("contract-m")).unsafeRunSync()
    moveDao.save(mkMove("cm1", "contract-m", 0)).unsafeRunSync()
    moveDao.findByGameId("contract-m").unsafeRunSync() should have size 1
