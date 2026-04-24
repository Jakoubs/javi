package chess.persistence.config

import com.typesafe.config.{Config, ConfigFactory}

/**
 * Reads the `chess.persistence` block from `application.conf` and exposes
 * strongly-typed configuration values.
 *
 * Environment-variable overrides are supported inline in the HOCON file
 * (e.g. `url = ${?CHESS_DB_URL}`), so this class does not need to touch
 * `System.getenv` directly.
 */
final class PersistenceConfig private (root: Config):

  private val cfg = root.getConfig("chess.persistence")

  /** The selected backend: either `"slick"` or `"mongo"`. */
  val backend: String = cfg.getString("backend")

  object slick:
    private val s = cfg.getConfig("slick")
    val url:      String = s.getString("url")
    val user:     String = s.getString("user")
    val password: String = s.getString("password")
    val driver:   String = s.getString("driver")
    val poolSize: Int    = s.getInt("pool-size")

  object mongo:
    private val m = cfg.getConfig("mongo")
    val uri:      String = m.getString("uri")
    val database: String = m.getString("database")

object PersistenceConfig:
  /** Load from the default `application.conf` on the classpath. */
  def load(): PersistenceConfig =
    new PersistenceConfig(ConfigFactory.load())

  /** Load from a custom [[Config]] object (useful in tests). */
  def from(config: Config): PersistenceConfig =
    new PersistenceConfig(config)
