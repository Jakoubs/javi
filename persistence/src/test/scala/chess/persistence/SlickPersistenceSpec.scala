package chess.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import slick.jdbc.H2Profile
import slick.jdbc.JdbcBackend.Database

import chess.persistence.config.PersistenceConfig
import chess.persistence.model.{MoveEvent, PersistedGame}
import chess.persistence.slickimpl.SlickPersistence

/**
 * Integration tests for [[SlickPersistence]] using an in-memory H2 database.
 *
 * No external PostgreSQL is required: H2's Postgres compatibility mode
 * handles the same DDL and DML that Slick generates for `PostgresProfile`.
 */
class SlickPersistenceSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  // ─── H2 in-memory DB ──────────────────────────────────────────────────────

  private val h2Url =
    "jdbc:h2:mem:chess_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"

  private val db = Database.forURL(
    url    = h2Url,
    driver = "org.h2.Driver"
  )

  private var slick: SlickPersistence = _

  override def beforeAll(): Unit =
    // Create schema and hold the instance for all tests
    slick = SlickPersistence.make(H2Profile, db).unsafeRunSync()

  override def afterAll(): Unit =
    slick.close().unsafeRunSync()
    db.close()

  // ─── Fixtures ─────────────────────────────────────────────────────────────

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private def mkGame(id: String, result: String = "ongoing"): PersistedGame =
    PersistedGame(
      id        = id,
      startFen  = startFen,
      finalFen  = startFen,
      pgn       = "",
      result    = result,
      createdAt = System.currentTimeMillis(),
      updatedAt = System.currentTimeMillis()
    )

  private def mkMove(id: String, gameId: String, moveNumber: Int): MoveEvent =
    MoveEvent(
      id         = id,
      gameId     = gameId,
      moveNumber = moveNumber,
      san        = s"e${moveNumber + 4}",
      uci        = s"e2e${moveNumber + 4}",
      fenAfter   = startFen,
      timestamp  = System.currentTimeMillis()
    )

  // ─── GameDao tests ────────────────────────────────────────────────────────

  test("save and findById roundtrip"):
    val game = mkGame("g1")
    slick.gameDao.save(game).unsafeRunSync()
    val found = slick.gameDao.findById("g1").unsafeRunSync()
    found shouldBe Some(game)

  test("findById returns None for unknown id"):
    slick.gameDao.findById("no-such-id").unsafeRunSync() shouldBe None

  test("findAll returns all saved games"):
    val g2 = mkGame("g2")
    val g3 = mkGame("g3")
    slick.gameDao.save(g2).unsafeRunSync()
    slick.gameDao.save(g3).unsafeRunSync()
    val all = slick.gameDao.findAll().unsafeRunSync()
    all.map(_.id) should contain allOf ("g1", "g2", "g3")

  test("update changes finalFen and result"):
    val game    = mkGame("g4")
    val newFen  = "8/8/8/8/8/8/8/4K3 w - - 0 50"
    slick.gameDao.save(game).unsafeRunSync()
    slick.gameDao.update(game.copy(finalFen = newFen, result = "white", updatedAt = 9999L)).unsafeRunSync()
    val updated = slick.gameDao.findById("g4").unsafeRunSync().get
    updated.finalFen shouldBe newFen
    updated.result   shouldBe "white"

  test("delete removes game and its move events"):
    val game = mkGame("g5")
    val m1   = mkMove("m1", "g5", 0)
    val m2   = mkMove("m2", "g5", 1)
    slick.gameDao.save(game).unsafeRunSync()
    slick.moveEventDao.save(m1).unsafeRunSync()
    slick.moveEventDao.save(m2).unsafeRunSync()
    slick.gameDao.delete("g5").unsafeRunSync()
    slick.gameDao.findById("g5").unsafeRunSync() shouldBe None
    slick.moveEventDao.findByGameId("g5").unsafeRunSync() shouldBe empty

  // ─── MoveEventDao tests ───────────────────────────────────────────────────

  test("save and findByGameId returns moves in order"):
    val game = mkGame("g6")
    slick.gameDao.save(game).unsafeRunSync()
    val moves = (0 until 5).map(i => mkMove(s"mv-g6-$i", "g6", i)).toList
    moves.foreach(m => slick.moveEventDao.save(m).unsafeRunSync())
    val found = slick.moveEventDao.findByGameId("g6").unsafeRunSync()
    found.map(_.moveNumber) shouldBe (0 until 5).toList

  test("deleteByGameId removes all moves for a game"):
    val game = mkGame("g7")
    slick.gameDao.save(game).unsafeRunSync()
    slick.moveEventDao.save(mkMove("mv-g7-0", "g7", 0)).unsafeRunSync()
    slick.moveEventDao.save(mkMove("mv-g7-1", "g7", 1)).unsafeRunSync()
    slick.moveEventDao.deleteByGameId("g7").unsafeRunSync()
    slick.moveEventDao.findByGameId("g7").unsafeRunSync() shouldBe empty

  test("findByGameId returns empty list for unknown gameId"):
    slick.moveEventDao.findByGameId("unknown").unsafeRunSync() shouldBe empty
