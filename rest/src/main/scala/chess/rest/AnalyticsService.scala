package chess.rest

import java.sql.{Connection, DriverManager, ResultSet}
import scala.util.{Try, Using}

case class GameResultRow(result: String, count: Long, percentage: Double)
case class PopularFirstMoveRow(san: String, count: Long)
case class PuzzleDifficultyRow(difficulty: String, count: Long, avgRating: Double, avgMoves: Double)
case class PuzzleThemeRow(theme: String, count: Long, avgRating: Double)
case class GamesOverTimeRow(date: String, gamesPlayed: Long, whiteWins: Long, blackWins: Long, draws: Long)

case class AnalyticsSummary(
  gameResults: List[GameResultRow],
  popularFirstMoves: List[PopularFirstMoveRow],
  puzzleDifficulty: List[PuzzleDifficultyRow],
  puzzleThemes: List[PuzzleThemeRow],
  gamesOverTime: List[GamesOverTimeRow]
)

object AnalyticsService:

  private val dbUrl  = sys.env.getOrElse("CHESS_DB_URL", sys.env.getOrElse("DB_URL", ""))
  private val dbUser = sys.env.getOrElse("CHESS_DB_USER", sys.env.getOrElse("DB_USER", ""))
  private val dbPass = sys.env.getOrElse("CHESS_DB_PASSWORD", sys.env.getOrElse("DB_PASS", ""))

  private def connect(): Option[Connection] =
    if dbUrl.isEmpty then None
    else Try {
      Class.forName("org.postgresql.Driver")
      DriverManager.getConnection(dbUrl, dbUser, dbPass)
    }.toOption

  def getSummary(): AnalyticsSummary =
    connect() match
      case None => AnalyticsSummary(Nil, Nil, Nil, Nil, Nil)
      case Some(conn) =>
        Using.resource(conn) { c =>
          AnalyticsSummary(
            gameResults = queryGameResults(c),
            popularFirstMoves = queryPopularFirstMoves(c),
            puzzleDifficulty = queryPuzzleDifficulty(c),
            puzzleThemes = queryPuzzleThemes(c),
            gamesOverTime = queryGamesOverTime(c)
          )
        }

  private def queryGameResults(c: Connection): List[GameResultRow] =
    Try {
      val sql = """SELECT "result", "Count", "Percentage" FROM spark_analytics_game_results"""
      val rs = c.createStatement().executeQuery(sql)
      val buf = scala.collection.mutable.ListBuffer.empty[GameResultRow]
      while rs.next() do
        buf += GameResultRow(rs.getString(1), rs.getLong(2), rs.getDouble(3))
      buf.toList
    }.getOrElse(Nil)

  private def queryPopularFirstMoves(c: Connection): List[PopularFirstMoveRow] =
    Try {
      val sql = """SELECT "san", "Count" FROM spark_analytics_popular_first_moves ORDER BY "Count" DESC LIMIT 10"""
      val rs = c.createStatement().executeQuery(sql)
      val buf = scala.collection.mutable.ListBuffer.empty[PopularFirstMoveRow]
      while rs.next() do
        buf += PopularFirstMoveRow(rs.getString(1), rs.getLong(2))
      buf.toList
    }.getOrElse(Nil)

  private def queryPuzzleDifficulty(c: Connection): List[PuzzleDifficultyRow] =
    Try {
      val sql = """SELECT "Difficulty", "Count", "AvgRating", "AvgMoves" FROM spark_analytics_puzzle_difficulty_analysis"""
      val rs = c.createStatement().executeQuery(sql)
      val buf = scala.collection.mutable.ListBuffer.empty[PuzzleDifficultyRow]
      while rs.next() do
        buf += PuzzleDifficultyRow(rs.getString(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4))
      buf.toList
    }.getOrElse(Nil)

  private def queryPuzzleThemes(c: Connection): List[PuzzleThemeRow] =
    Try {
      val sql = """SELECT "Theme", "Count", "AvgRating" FROM spark_analytics_puzzle_theme_analysis ORDER BY "Count" DESC LIMIT 20"""
      val rs = c.createStatement().executeQuery(sql)
      val buf = scala.collection.mutable.ListBuffer.empty[PuzzleThemeRow]
      while rs.next() do
        buf += PuzzleThemeRow(rs.getString(1), rs.getLong(2), rs.getDouble(3))
      buf.toList
    }.getOrElse(Nil)

  private def queryGamesOverTime(c: Connection): List[GamesOverTimeRow] =
    Try {
      val sql = """SELECT "Date"::text, "GamesPlayed", "WhiteWins", "BlackWins", "Draws" FROM spark_analytics_games_over_time ORDER BY "Date" ASC"""
      val rs = c.createStatement().executeQuery(sql)
      val buf = scala.collection.mutable.ListBuffer.empty[GamesOverTimeRow]
      while rs.next() do
        buf += GamesOverTimeRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))
      buf.toList
    }.getOrElse(Nil)
