package chess.view

import java.sql.{Connection, DriverManager, ResultSet}
import scala.util.{Try, Using}

case class AdminUser(
  id:               Long,
  username:         String,
  email:            String,
  isVerified:       Boolean,
  isBanned:         Boolean,
  createdAt:        String
)

case class AdminStats(
  totalUsers:      Int,
  verifiedUsers:   Int,
  bannedUsers:     Int,
  activeGames:     Int,
  totalMovesPlayed: Int
)

case class AdminGameInfo(
  gameId:      String,
  status:      String,
  moveCount:   Int,
  activeColor: String,
  fen:         String,
  spectateUrl: String
)

/** JDBC-backed service for admin operations.
 *  Reads DB credentials from ENV variables (same as the REST service).
 *  Falls back gracefully when no DB is configured (in-process only mode).
 */
object AdminService:

  private val dbUrl  = sys.env.getOrElse("CHESS_DB_URL", sys.env.getOrElse("DB_URL", ""))
  private val dbUser = sys.env.getOrElse("CHESS_DB_USER", sys.env.getOrElse("DB_USER", ""))
  private val dbPass = sys.env.getOrElse("CHESS_DB_PASSWORD", sys.env.getOrElse("DB_PASS", ""))

  private def connect(): Option[Connection] =
    if dbUrl.isEmpty then None
    else Try {
      Class.forName("org.postgresql.Driver")
      DriverManager.getConnection(dbUrl, dbUser, dbPass)
    }.toOption

  // ── User listing ─────────────────────────────────────────────────────────

  def listUsers(): List[AdminUser] =
    connect() match
      case None => Nil
      case Some(conn) =>
        Using.resource(conn) { c =>
          val sql = """
            SELECT id, username, email,
                   COALESCE(is_verified, false),
                   COALESCE(is_banned, false),
                   COALESCE(created_at::text, '')
            FROM users
            ORDER BY id DESC
          """
          val rs = c.createStatement().executeQuery(sql)
          val buf = scala.collection.mutable.ListBuffer.empty[AdminUser]
          while rs.next() do
            buf += AdminUser(
              id         = rs.getLong(1),
              username   = rs.getString(2),
              email      = rs.getString(3),
              isVerified = rs.getBoolean(4),
              isBanned   = rs.getBoolean(5),
              createdAt  = rs.getString(6)
            )
          buf.toList
        }

  // ── Single user ───────────────────────────────────────────────────────────

  def findUser(id: Long): Option[AdminUser] =
    connect() match
      case None => None
      case Some(conn) =>
        Using.resource(conn) { c =>
          val ps = c.prepareStatement("""
            SELECT id, username, email,
                   COALESCE(is_verified, false),
                   COALESCE(is_banned, false),
                   COALESCE(created_at::text, '')
            FROM users WHERE id = ?
          """)
          ps.setLong(1, id)
          val rs = ps.executeQuery()
          if rs.next() then
            Some(AdminUser(
              id         = rs.getLong(1),
              username   = rs.getString(2),
              email      = rs.getString(3),
              isVerified = rs.getBoolean(4),
              isBanned   = rs.getBoolean(5),
              createdAt  = rs.getString(6)
            ))
          else None
        }

  // ── Delete user ───────────────────────────────────────────────────────────

  def deleteUser(id: Long): Either[String, Unit] =
    connect() match
      case None => Left("No database configured")
      case Some(conn) =>
        Using.resource(conn) { c =>
          Try {
            val ps = c.prepareStatement("DELETE FROM users WHERE id = ?")
            ps.setLong(1, id)
            val rows = ps.executeUpdate()
            if rows > 0 then Right(()) else Left(s"User $id not found")
          }.toEither.left.map(_.getMessage).flatten
        }

  // ── Ban / unban ───────────────────────────────────────────────────────────

  def setBanned(id: Long, banned: Boolean): Either[String, AdminUser] =
    connect() match
      case None => Left("No database configured")
      case Some(conn) =>
        Using.resource(conn) { c =>
          Try {
            ensureBannedColumn(c)
            val ps = c.prepareStatement(
              "UPDATE users SET is_banned = ? WHERE id = ? RETURNING id, username, email, COALESCE(is_verified,false), is_banned, COALESCE(created_at::text,'')"
            )
            ps.setBoolean(1, banned)
            ps.setLong(2, id)
            val rs = ps.executeQuery()
            if rs.next() then
              Right(AdminUser(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), rs.getString(6)))
            else Left(s"User $id not found")
          }.toEither.left.map(_.getMessage).flatten
        }

  // ── Verify / unverify ─────────────────────────────────────────────────────

  def setVerified(id: Long, verified: Boolean): Either[String, AdminUser] =
    connect() match
      case None => Left("No database configured")
      case Some(conn) =>
        Using.resource(conn) { c =>
          Try {
            val ps = c.prepareStatement(
              "UPDATE users SET is_verified = ? WHERE id = ? RETURNING id, username, email, is_verified, COALESCE(is_banned,false), COALESCE(created_at::text,'')"
            )
            ps.setBoolean(1, verified)
            ps.setLong(2, id)
            val rs = ps.executeQuery()
            if rs.next() then
              Right(AdminUser(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), rs.getString(6)))
            else Left(s"User $id not found")
          }.toEither.left.map(_.getMessage).flatten
        }

  // ── Update username/email ─────────────────────────────────────────────────

  def updateUser(id: Long, username: String, email: String): Either[String, AdminUser] =
    connect() match
      case None => Left("No database configured")
      case Some(conn) =>
        Using.resource(conn) { c =>
          Try {
            val ps = c.prepareStatement(
              "UPDATE users SET username = ?, email = ? WHERE id = ? RETURNING id, username, email, COALESCE(is_verified,false), COALESCE(is_banned,false), COALESCE(created_at::text,'')"
            )
            ps.setString(1, username)
            ps.setString(2, email)
            ps.setLong(3, id)
            val rs = ps.executeQuery()
            if rs.next() then
              Right(AdminUser(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), rs.getString(6)))
            else Left(s"User $id not found")
          }.toEither.left.map(_.getMessage).flatten
        }

  // ── Stats ─────────────────────────────────────────────────────────────────

  def getStats(activeGames: Int, totalMovesPlayed: Int): AdminStats =
    connect() match
      case None =>
        AdminStats(0, 0, 0, activeGames, totalMovesPlayed)
      case Some(conn) =>
        Using.resource(conn) { c =>
          Try {
            ensureBannedColumn(c)
            val rs = c.createStatement().executeQuery("""
              SELECT
                COUNT(*)                                        AS total,
                COUNT(*) FILTER (WHERE is_verified = true)     AS verified,
                COUNT(*) FILTER (WHERE is_banned = true)       AS banned
              FROM users
            """)
            if rs.next() then
              AdminStats(
                totalUsers       = rs.getInt(1),
                verifiedUsers    = rs.getInt(2),
                bannedUsers      = rs.getInt(3),
                activeGames      = activeGames,
                totalMovesPlayed = totalMovesPlayed
              )
            else AdminStats(0, 0, 0, activeGames, totalMovesPlayed)
          }.getOrElse(AdminStats(0, 0, 0, activeGames, totalMovesPlayed))
        }

  // ── Schema helpers ────────────────────────────────────────────────────────

  /** Idempotently add is_banned column if it doesn't exist yet. */
  private def ensureBannedColumn(conn: Connection): Unit =
    Try {
      conn.createStatement().execute("""
        ALTER TABLE users ADD COLUMN IF NOT EXISTS is_banned BOOLEAN NOT NULL DEFAULT false
      """)
    }.recover { case _ => () }
