package chess.persistence

import cats.effect.IO
import slick.jdbc.PostgresProfile

import chess.persistence.config.PersistenceConfig
import chess.persistence.dao.{GameDao, MoveEventDao, UserDao, FriendshipDao, OpeningDao}
import chess.persistence.mongo.MongoPersistence
import chess.persistence.slickimpl.SlickPersistence

/**
 * Factory that constructs and wires the persistence layer from configuration.
 *
 * Callers only need to depend on the [[GameDao]] / [[MoveEventDao]] traits —
 * they never import anything from the `slickimpl` or `mongo` sub-packages directly.
 *
 * {{{
 *   PersistenceModule.build().flatMap { module =>
 *     module.gameDao.save(myGame) *>
 *     module.moveEventDao.save(myMove)
 *   }
 * }}}
 */
final class PersistenceModule private (
  val gameDao:       GameDao,
  val moveEventDao:  MoveEventDao,
  val userDao:       UserDao,
  val friendshipDao: FriendshipDao,
  val openingDao:    OpeningDao,
  private val closeF: IO[Unit]
):
  /** Release all resources (connection pool / MongoClient). */
  def close(): IO[Unit] = closeF

object PersistenceModule:

  /**
   * Build the persistence module using the default `application.conf`.
   *
   * The returned `IO` is suspended — no connection is opened until it runs.
   */
  def build(): IO[PersistenceModule] =
    build(PersistenceConfig.load())

  /**
   * Build the persistence module from a custom [[PersistenceConfig]].
   * Useful in tests where you may want to override config values.
   */
  def build(cfg: PersistenceConfig): IO[PersistenceModule] =
    cfg.backend.toLowerCase match
      case "slick" => buildSlick(cfg)
      case "mongo" => buildMongo(cfg)
      case other   => IO.raiseError(
        new IllegalArgumentException(
          s"Unknown chess.persistence.backend '$other'. Choose 'slick' or 'mongo'."
        )
      )

  // ─── Backend builders ─────────────────────────────────────────────────────

  private def buildSlick(cfg: PersistenceConfig): IO[PersistenceModule] =
    SlickPersistence
      .fromConfig(
        profile  = PostgresProfile,
        url      = cfg.slick.url,
        user     = cfg.slick.user,
        password = cfg.slick.password,
        driver   = cfg.slick.driver,
        poolSize = cfg.slick.poolSize
      )
      .map { slick =>
        new PersistenceModule(slick.gameDao, slick.moveEventDao, slick, slick, slick.openingDao, slick.close())
      }

  private def buildMongo(cfg: PersistenceConfig): IO[PersistenceModule] =
    MongoPersistence
      .make(cfg.mongo.uri, cfg.mongo.database)
      .map { mongo =>
        // Mongo social logic not implemented yet, so we pass null/mock if needed
        // For now, let's assume we use Slick for users.
        val dummyUserDao = null.asInstanceOf[UserDao]
        val dummyFriendDao = null.asInstanceOf[FriendshipDao]
        new PersistenceModule(mongo.gameDao, mongo.moveEventDao, dummyUserDao, dummyFriendDao, mongo.openingDao, mongo.close())
      }
