package chess.rest

import java.sql.{Connection, DriverManager, ResultSet}
import scala.util.{Try, Using}

case class GameResultRow(result: String, count: Long, percentage: Double)
case class PopularFirstMoveRow(san: String, count: Long)
case class PuzzleDifficultyRow(difficulty: String, count: Long, avgRating: Double, avgMoves: Double)
case class PuzzleThemeRow(theme: String, count: Long, avgRating: Double)
case class GamesOverTimeRow(date: String, gamesPlayed: Long, whiteWins: Long, blackWins: Long, draws: Long)
case class UserWinRateRow(username: String, gamesPlayed: Long, wins: Long, losses: Long, draws: Long, winRate: Double)
case class BotStatsRow(botName: String, gamesPlayed: Long, wins: Long, losses: Long, draws: Long, winRate: Double)

case class AnalyticsSummary(
  gameResults: List[GameResultRow],
  popularFirstMoves: List[PopularFirstMoveRow],
  puzzleDifficulty: List[PuzzleDifficultyRow],
  puzzleThemes: List[PuzzleThemeRow],
  gamesOverTime: List[GamesOverTimeRow],
  userWinRates: List[UserWinRateRow],
  botStats: List[BotStatsRow]
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

  def getSummary(username: Option[String] = None): AnalyticsSummary =
    connect() match
      case None => AnalyticsSummary(Nil, Nil, Nil, Nil, Nil, Nil, Nil)
      case Some(conn) =>
        Using.resource(conn) { c =>
          AnalyticsSummary(
            gameResults = queryGameResults(c, username),
            popularFirstMoves = queryPopularFirstMoves(c, username),
            puzzleDifficulty = queryPuzzleDifficulty(c),
            puzzleThemes = queryPuzzleThemes(c),
            gamesOverTime = queryGamesOverTime(c, username),
            userWinRates = queryUserWinRates(c),
            botStats = queryBotStats(c)
          )
        }

  private def queryGameResults(c: Connection, username: Option[String]): List[GameResultRow] =
    username match {
      case Some(name) =>
        Try {
          val sql =
            """SELECT 
                 CASE 
                   WHEN (white_player = ? AND result = 'white') OR (black_player = ? AND result = 'black') THEN 'Wins'
                   WHEN (white_player = ? AND result = 'black') OR (black_player = ? AND result = 'white') THEN 'Losses'
                   WHEN result = 'draw' THEN 'Draws'
                   ELSE 'Ongoing'
                 END as outcome,
                 COUNT(*) as "Count"
               FROM games
               WHERE white_player = ? OR black_player = ?
               GROUP BY outcome"""
          val pstmt = c.prepareStatement(sql)
          pstmt.setString(1, name)
          pstmt.setString(2, name)
          pstmt.setString(3, name)
          pstmt.setString(4, name)
          pstmt.setString(5, name)
          pstmt.setString(6, name)
          val rs = pstmt.executeQuery()
          val buf = scala.collection.mutable.ListBuffer.empty[GameResultRow]
          var total = 0L
          val temp = scala.collection.mutable.ListBuffer.empty[(String, Long)]
          while rs.next() do
            val outcome = rs.getString(1)
            val count = rs.getLong(2)
            total += count
            temp += ((outcome, count))
          temp.foreach { case (outcome, count) =>
            val pct = if (total > 0) (count * 100.0 / total) else 0.0
            buf += GameResultRow(outcome, count, pct)
          }
          buf.toList
        }.getOrElse(Nil)
      case None =>
        Try {
          val sql =
            """SELECT result, COUNT(*) as "Count", ROUND(COUNT(*) * 100.0 / NULLIF((SELECT COUNT(*) FROM games), 0), 1) as "Percentage"
               FROM games
               GROUP BY result"""
          val rs = c.createStatement().executeQuery(sql)
          val buf = scala.collection.mutable.ListBuffer.empty[GameResultRow]
          while rs.next() do
            buf += GameResultRow(rs.getString(1), rs.getLong(2), rs.getDouble(3))
          val list = buf.toList
          if (list.nonEmpty) list else throw new Exception("raw table empty")
        }.orElse(Try {
          val sql = """SELECT "result", "Count", "Percentage" FROM spark_analytics_game_results"""
          val rs = c.createStatement().executeQuery(sql)
          val buf = scala.collection.mutable.ListBuffer.empty[GameResultRow]
          while rs.next() do
            buf += GameResultRow(rs.getString(1), rs.getLong(2), rs.getDouble(3))
          buf.toList
        }).getOrElse(Nil)
    }

  private def queryPopularFirstMoves(c: Connection, username: Option[String]): List[PopularFirstMoveRow] =
    username match {
      case Some(name) =>
        Try {
          val sql =
            """SELECT m.san, COUNT(*) as "Count"
               FROM move_events m
               JOIN games g ON m.game_id = g.id
               WHERE ((g.white_player = ? AND m.move_number = 0) OR (g.black_player = ? AND m.move_number = 1))
               GROUP BY m.san
               ORDER BY "Count" DESC
               LIMIT 10"""
          val pstmt = c.prepareStatement(sql)
          pstmt.setString(1, name)
          pstmt.setString(2, name)
          val rs = pstmt.executeQuery()
          val buf = scala.collection.mutable.ListBuffer.empty[PopularFirstMoveRow]
          while rs.next() do
            buf += PopularFirstMoveRow(rs.getString(1), rs.getLong(2))
          buf.toList
        }.getOrElse(Nil)
      case None =>
        Try {
          val sql =
            """SELECT san, COUNT(*) as "Count"
               FROM move_events
               WHERE move_number = 0
               GROUP BY san
               ORDER BY "Count" DESC
               LIMIT 10"""
          val rs = c.createStatement().executeQuery(sql)
          val buf = scala.collection.mutable.ListBuffer.empty[PopularFirstMoveRow]
          while rs.next() do
            buf += PopularFirstMoveRow(rs.getString(1), rs.getLong(2))
          val list = buf.toList
          if (list.nonEmpty) list else throw new Exception("raw table empty")
        }.orElse(Try {
          val sql = """SELECT "san", "Count" FROM spark_analytics_popular_first_moves ORDER BY "Count" DESC LIMIT 10"""
          val rs = c.createStatement().executeQuery(sql)
          val buf = scala.collection.mutable.ListBuffer.empty[PopularFirstMoveRow]
          while rs.next() do
            buf += PopularFirstMoveRow(rs.getString(1), rs.getLong(2))
          buf.toList
        }).getOrElse(Nil)
    }

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

  private def queryGamesOverTime(c: Connection, username: Option[String]): List[GamesOverTimeRow] =
    username match {
      case Some(name) =>
        Try {
          val sql =
            """SELECT TO_CHAR(TO_TIMESTAMP(created_at / 1000.0), 'YYYY-MM-DD') as "Date",
                      COUNT(*) as "GamesPlayed",
                      SUM(CASE WHEN (white_player = ? AND result = 'white') OR (black_player = ? AND result = 'black') THEN 1 ELSE 0 END) as "WhiteWins",
                      SUM(CASE WHEN (white_player = ? AND result = 'black') OR (black_player = ? AND result = 'white') THEN 1 ELSE 0 END) as "BlackWins",
                      SUM(CASE WHEN result = 'draw' THEN 1 ELSE 0 END) as "Draws"
               FROM games
               WHERE white_player = ? OR black_player = ?
               GROUP BY "Date"
               ORDER BY "Date" ASC"""
          val pstmt = c.prepareStatement(sql)
          pstmt.setString(1, name)
          pstmt.setString(2, name)
          pstmt.setString(3, name)
          pstmt.setString(4, name)
          pstmt.setString(5, name)
          pstmt.setString(6, name)
          val rs = pstmt.executeQuery()
          val buf = scala.collection.mutable.ListBuffer.empty[GamesOverTimeRow]
          while rs.next() do
            buf += GamesOverTimeRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))
          buf.toList
        }.getOrElse(Nil)
      case None =>
        Try {
          val sql =
            """SELECT TO_CHAR(TO_TIMESTAMP(created_at / 1000.0), 'YYYY-MM-DD') as "Date",
                      COUNT(*) as "GamesPlayed",
                      SUM(CASE WHEN result = 'white' THEN 1 ELSE 0 END) as "WhiteWins",
                      SUM(CASE WHEN result = 'black' THEN 1 ELSE 0 END) as "BlackWins",
                      SUM(CASE WHEN result = 'draw' THEN 1 ELSE 0 END) as "Draws"
               FROM games
               GROUP BY "Date"
               ORDER BY "Date" ASC"""
          val rs = c.createStatement().executeQuery(sql)
          val buf = scala.collection.mutable.ListBuffer.empty[GamesOverTimeRow]
          while rs.next() do
            buf += GamesOverTimeRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))
          val list = buf.toList
          if (list.nonEmpty) list else throw new Exception("raw table empty")
        }.orElse(Try {
          val sql = """SELECT "Date"::text, "GamesPlayed", "WhiteWins", "BlackWins", "Draws" FROM spark_analytics_games_over_time ORDER BY "Date" ASC"""
          val rs = c.createStatement().executeQuery(sql)
          val buf = scala.collection.mutable.ListBuffer.empty[GamesOverTimeRow]
          while rs.next() do
            buf += GamesOverTimeRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))
          buf.toList
        }).getOrElse(Nil)
    }

  private def queryUserWinRates(c: Connection): List[UserWinRateRow] =
    Try {
      val sql =
        """SELECT 
             username,
             COUNT(*) as games_played,
             SUM(CASE WHEN (role = 'white' AND result = 'white') OR (role = 'black' AND result = 'black') THEN 1 ELSE 0 END) as wins,
             SUM(CASE WHEN (role = 'white' AND result = 'black') OR (role = 'black' AND result = 'white') THEN 1 ELSE 0 END) as losses,
             SUM(CASE WHEN result = 'draw' THEN 1 ELSE 0 END) as draws,
             ROUND(SUM(CASE WHEN (role = 'white' AND result = 'white') OR (role = 'black' AND result = 'black') THEN 1.0 ELSE 0.0 END) * 100.0 / NULLIF(COUNT(*), 0), 1) as win_rate
           FROM (
             SELECT white_player as username, 'white' as role, result FROM games WHERE white_player NOT LIKE 'bot:%' AND white_player != 'guest'
             UNION ALL
             SELECT black_player as username, 'black' as role, result FROM games WHERE black_player NOT LIKE 'bot:%' AND black_player != 'guest'
           ) as user_games
           GROUP BY username
           ORDER BY wins DESC, games_played DESC
           LIMIT 10"""
      val rs = c.createStatement().executeQuery(sql)
      val buf = scala.collection.mutable.ListBuffer.empty[UserWinRateRow]
      while rs.next() do
        buf += UserWinRateRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getDouble(6))
      buf.toList
    }.getOrElse(Nil)

  private def queryBotStats(c: Connection): List[BotStatsRow] =
    Try {
      val sql =
        """SELECT 
             bot_name,
             COUNT(*) as games_played,
             SUM(CASE WHEN (role = 'white' AND result = 'white') OR (role = 'black' AND result = 'black') THEN 1 ELSE 0 END) as wins,
             SUM(CASE WHEN (role = 'white' AND result = 'black') OR (role = 'black' AND result = 'white') THEN 1 ELSE 0 END) as losses,
             SUM(CASE WHEN result = 'draw' THEN 1 ELSE 0 END) as draws,
             ROUND(SUM(CASE WHEN (role = 'white' AND result = 'white') OR (role = 'black' AND result = 'black') THEN 1.0 ELSE 0.0 END) * 100.0 / NULLIF(COUNT(*), 0), 1) as win_rate
           FROM (
             SELECT white_player as bot_name, 'white' as role, result FROM games WHERE white_player LIKE 'bot:%'
             UNION ALL
             SELECT black_player as bot_name, 'black' as role, result FROM games WHERE black_player LIKE 'bot:%'
           ) as bot_games
           GROUP BY bot_name
           ORDER BY wins DESC, games_played DESC"""
      val rs = c.createStatement().executeQuery(sql)
      val buf = scala.collection.mutable.ListBuffer.empty[BotStatsRow]
      while rs.next() do
        buf += BotStatsRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getDouble(6))
      buf.toList
    }.getOrElse(Nil)
