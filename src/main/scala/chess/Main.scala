package chess

import chess.controller.GameController
import chess.view.{Tui, Gui}
import chess.rest.{AuthService, EmailService, Http4sRestApi, KafkaService}
import chess.persistence.PersistenceModule
import chess.persistence.dao.{FriendshipDao, OpeningDao, UserDao, PuzzleDao}
import chess.persistence.model.User
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s.*

object Main:
  private var activeRestApi: Option[cats.effect.FiberIO[Nothing]] = None
  private var shutdownHooks: List[IO[Unit]] = Nil

  def main(args: Array[String]): Unit =
    setup(args)
    // Start ScalaFX GUI (takes over the main thread)
    Gui.main(args)

  def setup(args: Array[String]): Unit =
    val tui = new Tui()
    GameController.addObserver(tui)

    val restApi = buildRestApi()
    
    val serverConfig = EmberServerBuilder.default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(restApi.app)
      .build

    // Start server in background using cats-effect Fiber
    activeRestApi = Some(serverConfig.useForever.start.unsafeRunSync())
    println("[REST API] Server online at http://localhost:8080/")

    // TUI blocks on readLine, so we push it to a background thread
    Future { tui.run() }

  def shutdown(): Unit =
    activeRestApi.foreach(_.cancel.unsafeRunSync())
    activeRestApi = None
    shutdownHooks.foreach(_.unsafeRunSync())
    shutdownHooks = Nil

  private def buildRestApi(): Http4sRestApi =
    try
      val persistence = PersistenceModule.build().unsafeRunSync()
      val emailService = EmailService.fromEnv().unsafeRunSync()
      val authService = new AuthService(persistence.userDao, emailService)
      shutdownHooks = persistence.close() :: shutdownHooks
      new Http4sRestApi(KafkaService.noOp, authService, persistence.friendshipDao, persistence.openingDao, persistence.puzzleDao)
    catch
      case _: Throwable =>
        val userDao = InMemoryUserDao
        val friendshipDao = InMemoryFriendshipDao
        val authService = new AuthService(userDao, new EmailService("", "", "", "", "noreply@localhost"))
        new Http4sRestApi(KafkaService.noOp, authService, friendshipDao, InMemoryOpeningDao, InMemoryPuzzleDao)

  private object InMemoryUserDao extends UserDao:
    override def save(user: User): IO[Long] = IO.pure(1L)
    override def findByUsername(username: String): IO[Option[User]] = IO.pure(None)
    override def findByEmail(email: String): IO[Option[User]] = IO.pure(None)
    override def findByVerificationToken(token: String): IO[Option[User]] = IO.pure(None)
    override def verifyUser(token: String): IO[Boolean] = IO.pure(false)
    override def findById(id: Long): IO[Option[User]] = IO.pure(None)

  private object InMemoryFriendshipDao extends FriendshipDao:
    override def addFriend(userId: Long, friendId: Long): IO[Unit] = IO.unit
    override def getFriends(userId: Long): IO[List[User]] = IO.pure(Nil)
    override def acceptFriend(userId: Long, friendId: Long): IO[Unit] = IO.unit
    override def getPendingRequests(userId: Long): IO[List[User]] = IO.pure(Nil)

  private object InMemoryOpeningDao extends OpeningDao:
    override def findByFen(fen: String): IO[List[chess.persistence.model.Opening]] = IO.pure(Nil)
    override def save(opening: chess.persistence.model.Opening): IO[Unit] = IO.unit
    override def count(): IO[Long] = IO.pure(0L)
    override def deleteAll(): IO[Unit] = IO.unit

  private object InMemoryPuzzleDao extends PuzzleDao:
    override def save(puzzle: chess.persistence.model.Puzzle): IO[Unit] = IO.unit
    override def saveBatch(puzzles: List[chess.persistence.model.Puzzle]): IO[Unit] = IO.unit
    override def getRandom(theme: Option[String] = None): IO[Option[chess.persistence.model.Puzzle]] = IO.pure(None)
    override def findByTheme(theme: String, desc: Boolean = false, limit: Int = 20, offset: Int = 0): IO[List[chess.persistence.model.Puzzle]] = IO.pure(Nil)
    override def countPuzzles(): IO[Long] = IO.pure(0L)
    override def saveTheme(theme: chess.persistence.model.PuzzleTheme): IO[Unit] = IO.unit
    override def findAllThemes(): IO[List[chess.persistence.model.PuzzleTheme]] = IO.pure(Nil)
