package chess.spark

import com.typesafe.config.{Config, ConfigFactory}
import java.util.Properties

/**
 * Type-safe configuration for the Chess Spark analytics module.
 *
 * Reads from `application.conf` with environment-variable overrides.
 * All values are eagerly resolved at construction time.
 */
case class SparkConfig(
  dbUrl:             String,
  dbUser:            String,
  dbPassword:        String,
  dbDriver:          String,
  kafkaBootstrap:    String,
  topicGameReports:  String,
  topicMoves:        String,
  topicEvaluations:  String,
  puzzleCsvPath:     String,
  outputJsonDir:     String,
  analyticsTable:    String,
  enablePuzzle:      Boolean
) {
  /** JDBC connection properties for Spark's `read.jdbc()`. */
  def jdbcProperties: Properties = {
    val props = new Properties()
    props.setProperty("user",     dbUser)
    props.setProperty("password", dbPassword)
    props.setProperty("driver",   dbDriver)
    props
  }
}

object SparkConfig {
  def load(): SparkConfig = {
    val conf: Config = ConfigFactory.load().getConfig("chess-spark")

    SparkConfig(
      dbUrl             = conf.getString("db.url"),
      dbUser            = conf.getString("db.user"),
      dbPassword        = conf.getString("db.password"),
      dbDriver          = conf.getString("db.driver"),
      kafkaBootstrap    = conf.getString("kafka.bootstrap-servers"),
      topicGameReports  = conf.getString("kafka.topics.game-reports"),
      topicMoves        = conf.getString("kafka.topics.moves"),
      topicEvaluations  = conf.getString("kafka.topics.evaluations"),
      puzzleCsvPath     = conf.getString("files.puzzle-csv"),
      outputJsonDir     = conf.getString("output.json-dir"),
      analyticsTable    = conf.getString("output.analytics-table"),
      enablePuzzle      = conf.getBoolean("files.enable-puzzle")
    )
  }
}
