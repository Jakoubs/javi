package chess.persistence.slickimpl

import cats.effect.IO
import slick.jdbc.JdbcProfile
import slick.jdbc.JdbcBackend.Database

import chess.persistence.dao.{GameDao, MoveEventDao, UserDao, FriendshipDao}
import chess.persistence.model.{MoveEvent, PersistedGame, User, Friendship}

/**
 * Unified Slick implementation of both [[GameDao]] and [[MoveEventDao]].
 *
 * Uses HikariCP for connection pooling (configured via [[chess.persistence.config.PersistenceConfig]]).
 * All Slick `Future`-based actions are lifted into `IO` via `IO.fromFuture`.
 *
 * Call [[SlickPersistence.make]] to create an instance and run DDL.
 *
 * {{{
 *   SlickPersistence.make(profile, db).flatMap { slick =>
 *     slick.gameDao.save(game) *> slick.moveEventDao.save(event)
 *   }
 * }}}
 */
final class SlickPersistence private (
  val profile:      JdbcProfile,
  private val db:   Database,
  private val gTbl: GameTable,
  private val mTbl: MoveEventTable,
  private val uTbl: UserTable
) extends GameDao with MoveEventDao with UserDao with FriendshipDao:

  import profile.api.*

  // ─── Helpers ──────────────────────────────────────────────────────────────

  /** Run a DBIO action and lift the resulting Future into IO. */
  private def run[A](action: DBIO[A]): IO[A] =
    IO.fromFuture(IO(db.run(action)))

  // ─── GameDao ──────────────────────────────────────────────────────────────

  override def save(game: PersistedGame): IO[Unit] =
    run(gTbl.games += game).void

  override def findById(id: String): IO[Option[PersistedGame]] =
    run(gTbl.games.filter(_.id === id).result.headOption)

  override def findAll(): IO[List[PersistedGame]] =
    run(gTbl.games.sortBy(_.createdAt.desc).result).map(_.toList)

  override def update(game: PersistedGame): IO[Unit] =
    val q = gTbl.games.filter(_.id === game.id)
      .map(r => (r.finalFen, r.pgn, r.result, r.updatedAt))
      .update((game.finalFen, game.pgn, game.result, game.updatedAt))
    run(q).void

  override def delete(id: String): IO[Unit] =
    // Delete move events first (avoids FK violation if DB enforces the constraint)
    val deleteMoves = mTbl.moveEvents.filter(_.gameId === id).delete
    val deleteGame  = gTbl.games.filter(_.id === id).delete
    run(deleteMoves >> deleteGame).void

  // ─── MoveEventDao ─────────────────────────────────────────────────────────

  override def save(event: MoveEvent): IO[Unit] =
    run(mTbl.moveEvents += event).void

  override def findByGameId(gameId: String): IO[List[MoveEvent]] =
    run(
      mTbl.moveEvents
        .filter(_.gameId === gameId)
        .sortBy(_.moveNumber.asc)
        .result
    ).map(_.toList)

  override def deleteByGameId(gameId: String): IO[Unit] =
    run(mTbl.moveEvents.filter(_.gameId === gameId).delete).void

  // ─── UserDao ──────────────────────────────────────────────────────────────

  override def save(user: User): IO[Long] =
    run((uTbl.users returning uTbl.users.map(_.id)) += user)

  override def findByUsername(username: String): IO[Option[User]] =
    run(uTbl.users.filter(_.username === username).result.headOption)

  override def findById(id: Long): IO[Option[User]] =
    run(uTbl.users.filter(_.id === id).result.headOption)

  // ─── FriendshipDao ────────────────────────────────────────────────────────

  override def addFriend(userId: Long, friendId: Long): IO[Unit] =
    run(uTbl.friendships += Friendship(userId, friendId, "accepted")).void

  override def getFriends(userId: Long): IO[List[User]] =
    val q = uTbl.friendships
      .filter(_.userId === userId)
      .join(uTbl.users)
      .on(_.friendId === _.id)
      .map(_._2)
    run(q.result).map(_.toList)

  override def acceptFriend(userId: Long, friendId: Long): IO[Unit] =
    run(uTbl.friendships.filter(_.userId === userId).filter(_.friendId === friendId).map(_.status).update("accepted")).void

  /** Release the underlying HikariCP connection pool. */
  def close(): IO[Unit] = IO(db.close())

  /** Convenience: expose both DAO interfaces individually */
  def gameDao:      GameDao      = this
  def moveEventDao: MoveEventDao = this

object SlickPersistence:

  /**
   * Create a [[SlickPersistence]] instance, running DDL (`CREATE TABLE IF NOT EXISTS`)
   * before returning.
   *
   * @param profile The JDBC profile to use (`PostgresProfile` in prod, `H2Profile` in tests).
   * @param db      An already-opened Slick [[Database]].
   */
  def make(profile: JdbcProfile, db: Database): IO[SlickPersistence] =
    val gTbl = GameTable(profile)
    val mTbl = MoveEventTable(profile)
    val uTbl = UserTable(profile)
    val instance = new SlickPersistence(profile, db, gTbl, mTbl, uTbl)
    import profile.api.*
    val ddl = gTbl.createSchema >> mTbl.createSchema >> uTbl.createSchema
    IO.fromFuture(IO(db.run(ddl))).as(instance)

  /**
   * Convenience factory that opens a HikariCP pool from the supplied config values.
   */
  def fromConfig(
    profile:  JdbcProfile,
    url:      String,
    user:     String,
    password: String,
    driver:   String,
    poolSize: Int
  ): IO[SlickPersistence] =
    val db = Database.forURL(
      url      = url,
      user     = user,
      password = password,
      driver   = driver,
      executor = slick.util.AsyncExecutor("chess-db", poolSize, poolSize, 1000, poolSize)
    )
    make(profile, db)
