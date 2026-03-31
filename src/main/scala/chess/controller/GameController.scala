package chess.controller

import chess.model.*
import chess.view.TerminalView

trait ConsoleIO:
  def readLine(): Option[String]
  def print(text: String): Unit
  def clear(): Unit

object StdConsoleIO extends ConsoleIO:
  def readLine(): Option[String] =
    Option(scala.io.StdIn.readLine())

  def print(text: String): Unit =
    scala.Predef.print(text)

  def clear(): Unit =
    TerminalView.clear()

// ─── Command ADT ─────────────────────────────────────────────────────────────

enum Command:
  case ProcessTurn(input: String)
  case ShowMoves(pos: Pos)
  case Flip
  case Undo
  case Resign
  case OfferDraw
  case NewGame
  case Help
  case Quit
  case AiMove
  case AiTrain(n: Int)
  case PlayVsAi   // toggle: human vs AI mode
  case Unknown(input: String)

// ─── CommandParser ────────────────────────────────────────────────────────────

object CommandParser:

  def parse(input: String): Command =
    val trimmed = input.trim.toLowerCase

    trimmed match
      case "flip"               => Command.Flip
      case "undo"               => Command.Undo
      case "resign"             => Command.Resign
      case "draw"               => Command.OfferDraw
      case "new" | "newgame"    => Command.NewGame
      case "help" | "?"         => Command.Help
      case "quit" | "exit" | "q"=> Command.Quit
      case "ai"                 => Command.AiMove
      case "vsai" | "playai"   => Command.PlayVsAi
      case s if s.startsWith("train ") =>
        val nStr = s.drop(6).trim
        nStr.toIntOption match
          case Some(n) if n > 0 => Command.AiTrain(n)
          case _                => Command.Unknown(s"Invalid training count: $nStr")
      case s if s.startsWith("moves ") =>
        val posStr = s.drop(6).trim
        Pos.fromAlgebraic(posStr) match
          case Some(pos) => Command.ShowMoves(pos)
          case None      => Command.Unknown(s"Invalid position: $posStr")
      case s if (s.length >= 4 && s.length <= 7) =>
        val normalized = s.replace("-", "")
        if (normalized.length == 4 || normalized.length == 5) then
          val fromPos = Pos.fromAlgebraic(normalized.take(2))
          val toPos   = Pos.fromAlgebraic(normalized.substring(2, 4))
          val promo   = normalized.drop(4)
          val validPromo = promo.isEmpty || (promo.length == 1 && "qrbn".contains(promo.head))
          
          if fromPos.isDefined && toPos.isDefined && validPromo then
            Command.ProcessTurn(s)
          else
            Command.Unknown(s"Invalid move format: $s")
        else
          Command.Unknown(s"Invalid move format: $s")
      case s =>
        Command.Unknown(s"Unknown command: $s")

object MoveParser:
  import scala.util.Try
  def parseInput(input: String): Try[Move] = Try {
    val cleaned = input.trim.toLowerCase
    val parts = if cleaned.contains('-') then cleaned.split('-').mkString else cleaned
    if parts.length < 4 then throw new IllegalArgumentException("Input too short")

    val fromStr   = parts.take(2)
    val toStr     = parts.substring(2, 4)
    val promoChar = parts.drop(4).headOption

    val from = Pos.fromAlgebraic(fromStr).getOrElse(throw new IllegalArgumentException(s"Ungültiges Startfeld: $fromStr"))
    val to   = Pos.fromAlgebraic(toStr).getOrElse(throw new IllegalArgumentException(s"Ungültiges Zielfeld: $toStr"))

    val promo = promoChar match
      case Some('q') => Some(PieceType.Queen)
      case Some('r') => Some(PieceType.Rook)
      case Some('b') => Some(PieceType.Bishop)
      case Some('n') => Some(PieceType.Knight)
      case Some(c)   => throw new IllegalArgumentException(s"Ungültige Promotion: $c")
      case None      => None

    Move(from, to, promo)
  }

// ─── AppState ─────────────────────────────────────────────────────────────────

/** Everything the controller needs to know about the current UI state */
case class AppState(
  game:        GameState,
  status:      GameStatus,
  flipped:     Boolean         = false,
  highlights:  Set[Pos]        = Set.empty,
  lastMove:    Option[Move]    = None,
  message:     Option[String]  = None,
  drawOffer:   Boolean         = false,
  running:     Boolean         = true,
  training:    Boolean         = false,
  aiOpponent:  Boolean         = false   // when true, AI auto-replies after each human move
)

object AppState:
  def initial: AppState =
    val game   = GameState.initial
    val status = GameRules.computeStatus(game)
    AppState(game = game, status = status)

  def initialVsAi: AppState = initial.copy(aiOpponent = true)

// ─── GameController ───────────────────────────────────────────────────────────

object GameController:
  import scala.concurrent.Future
  import scala.concurrent.ExecutionContext
  import java.util.concurrent.Executors

  // Unbounded cached thread pool: creates a new thread per game if needed.
  // Much more concurrent than the default ForkJoinPool (which caps at CPU cores).
  private val trainingEC: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()))

  // ── Main loop ──────────────────────────────────────────────────────────────

  def run(console: ConsoleIO = StdConsoleIO): Unit =
    chess.ai.Evaluator.loadWeights()
    var app = AppState.initial
    renderFull(app, console)

    while app.running do
      console.print(TerminalView.prompt)
      console.readLine() match
        case Some(input) =>
          app = handleCommand(app, CommandParser.parse(input.trim))
          // If vs-AI mode: let AI reply once after each human command
          if app.aiOpponent && app.status == GameStatus.Playing then
            app = handleAiMove(app)
          renderFull(app, console)
        case None =>
          app = app.copy(running = false)

  // ── Render ─────────────────────────────────────────────────────────────────

  private def renderFull(app: AppState, console: ConsoleIO): Unit =
    console.clear()
    console.print(TerminalView.render(
      state      = app.game,
      status     = app.status,
      highlights = app.highlights,
      lastMove   = app.lastMove,
      flipped    = app.flipped
    ))
    if app.training then
      console.print(TerminalView.info("AI is currently training in the background..."))
    if app.aiOpponent then
      console.print(TerminalView.info(s"Playing vs AI | You are ${app.game.activeColor} | Type 'vsai' to disable"))
    app.message.foreach(console.print)

  // ── Command dispatch ───────────────────────────────────────────────────────

  def handleCommand(app: AppState, cmd: Command): AppState =
    import Command.*
    cmd match
      case ProcessTurn(input) => handleTurn(app, input)
      case ShowMoves(pos) => handleShowMoves(app, pos)
      case Flip           => app.copy(highlights = Set.empty, message = None, flipped = !app.flipped)
      case Undo           => handleUndo(app)
      case Resign         => handleResign(app)
      case OfferDraw      => handleDraw(app)
      case NewGame        => AppState.initial.copy(aiOpponent = app.aiOpponent, message = Some(TerminalView.info("New game started.")))
      case Help           => app.copy(message = Some(TerminalView.helpText))
      case Quit           => app.copy(running = false, message = None)
      case AiMove         => handleAiMove(app)
      case AiTrain(n)     => handleAiTrain(app, n)
      case PlayVsAi       => handleToggleVsAi(app)
      case Unknown(msg)   => app.copy(message = Some(TerminalView.error(msg)), highlights = Set.empty)

  // ── Move handler ───────────────────────────────────────────────────────────

  private def processTurn(input: String, state: GameState): Either[String, (Move, GameState)] =
    for {
      // 1. Unpack Try: Input validieren
      moveCoords <- MoveParser.parseInput(input).toEither.left.map(e => s"Ungültiges Format! ${e.getMessage}")
      
      // 2. Unpack Option: Ist auf dem Startfeld eine Figur?
      piece <- state.board.get(moveCoords.from).toRight("Feld ist leer!")
      
      // 3. Either-Logik: Turn-Check (State-Pattern)
      _ <- Either.cond(
             piece.color == state.activeColor,
             (),
             s"Falsche Farbe! ${state.activeColor} ist am Zug."
           )
           
      // 4. Either-Logik: Legal-Move-Check
      legalMoves = MoveGenerator.legalMoves(state)
      _ <- Either.cond(
             legalMoves.exists(m => m == moveCoords),
             (),
             if legalMoves.exists(m => m.from == moveCoords.from && m.to == moveCoords.to && m.promotion.isDefined) && moveCoords.promotion.isEmpty then
               "Pawn promotion required. Append q/r/b/n (e.g. e7e8q)."
             else "Illegaler Zug."
           )
           
      // 5. State-Update: Neues Board erzeugen
      newState = GameRules.applyMove(state, moveCoords)
    } yield (moveCoords, newState)

  private def handleTurn(app: AppState, input: String): AppState =
    // Game already over?
    val isGameOver = app.status match
      case GameStatus.Checkmate(_) | GameStatus.Stalemate | GameStatus.Draw(_) => true
      case _ => false

    if isGameOver then
      app.copy(message = Some(TerminalView.error("Game is over. Type 'new' to start again.")))
    else
      // Run Monadic Railway
      processTurn(input, app.game) match {
        case Right((move, newGame)) =>
          val newStatus = GameRules.computeStatus(newGame)
          val msg = newStatus match
            case GameStatus.Checkmate(loser)  =>
              val winner = if loser == Color.White then "Black" else "White"
              Some(TerminalView.success(s"Checkmate! $winner wins!"))
            case GameStatus.Stalemate        => Some(TerminalView.info("Stalemate — draw!"))
            case GameStatus.Draw(reason)     => Some(TerminalView.info(s"Draw by $reason."))
            case GameStatus.Check(_)         => Some(TerminalView.info("Check!"))
            case GameStatus.Playing          => None

          val nextApp = app.copy(
            game       = newGame,
            status     = newStatus,
            lastMove   = Some(move),
            highlights = Set.empty,
            message    = msg,
            drawOffer  = false
          )
          
          if nextApp.status != GameStatus.Playing then
            chess.ai.PassiveTrainer.train(nextApp.game, nextApp.status)
          
          nextApp

        case Left(errorMsg) =>
          app.copy(message = Some(TerminalView.error(errorMsg)), highlights = Set.empty)
      }

  private val MAX_MOVES_PER_GAME = 200

  private def simulateGame(): Unit =
    var gameApp = AppState.initial
    var moveCount = 0
    while gameApp.status == GameStatus.Playing && moveCount < MAX_MOVES_PER_GAME do
      val move = chess.ai.AiEngine.bestMove(gameApp.game, 2, epsilon = 0.1)
      move match
        case Some(m) => gameApp = handleTurn(gameApp, m.toInputString)
        case None    => gameApp = gameApp.copy(status = GameStatus.Draw("no legal moves"))
      moveCount += 1
    if gameApp.status == GameStatus.Playing then
      gameApp = gameApp.copy(status = GameStatus.Draw("max moves reached"))

  private def handleToggleVsAi(app: AppState): AppState =
    if app.aiOpponent then
      app.copy(aiOpponent = false, message = Some(TerminalView.info("AI opponent disabled.")))
    else
      val msg = TerminalView.success(s"AI opponent enabled! You play as ${app.game.activeColor}. AI will auto-reply.")
      app.copy(aiOpponent = true, message = Some(msg))

  private def handleAiTrain(initialApp: AppState, numGames: Int): AppState =
    import java.util.concurrent.atomic.AtomicInteger
    given ExecutionContext = trainingEC  // all Future ops in this scope use trainingEC

    val completed = AtomicInteger(0)
    val checkpointInterval = 50000 // user suggested 50,000 blocks

    // Launch games on the fixed thread pool
    val allGames = Future.traverse((1 to numGames).toList) { _ =>
      Future(simulateGame()).map { _ =>
        val done = completed.incrementAndGet()
        
        // Progress message
        if (done % 500 == 0 || done == numGames) then
          println(s"Training progress: $done/$numGames games completed.")
          
        // Checkpoint saving
        if (done % checkpointInterval == 0) then
          chess.ai.Evaluator.saveWeights()
          println(s"Checkpoint reached ($done games): Weights saved to Google Drive/local disk.")
      }
    }

    allGames.foreach { _ =>
      chess.ai.Evaluator.saveWeights()
      println(s"Training session completed! All $numGames games done. Final weights saved.")
    }

    initialApp.copy(
      training = true,
      message = Some(TerminalView.success(s"Started parallel training: $numGames games. Checkpoints every $checkpointInterval."))
    )

  private def handleAiMove(app: AppState): AppState =
    if app.status != GameStatus.Playing then
       app.copy(message = Some(TerminalView.error("Game is already over.")))
    else
      chess.ai.AiEngine.bestMove(app.game, 3) match
        case Some(move) => 
          handleTurn(app, move.toInputString)
        case None => 
          app.copy(message = Some(TerminalView.error("AI found no legal moves.")))

  // ── Show legal moves for a square ─────────────────────────────────────────

  private def handleShowMoves(app: AppState, pos: Pos): AppState =
    app.game.board.get(pos) match
      case None =>
        app.copy(message = Some(TerminalView.error(s"No piece on ${pos.toAlgebraic}.")), highlights = Set.empty)
      case Some(piece) if piece.color != app.game.activeColor =>
        app.copy(message = Some(TerminalView.error("That is not your piece.")), highlights = Set.empty)
      case Some(_) =>
        val targets = MoveGenerator.legalMovesFrom(app.game, pos).map(_.to).toSet
        if targets.isEmpty then
          app.copy(message = Some(TerminalView.info("No legal moves for that piece.")), highlights = Set.empty)
        else
          app.copy(highlights = targets, message = None)

  // ── Undo ───────────────────────────────────────────────────────────────────

  private def handleUndo(app: AppState): AppState =
    app.game.history.lastOption match
      case None =>
        app.copy(message = Some(TerminalView.error("Nothing to undo.")))
      case Some(prev) =>
        val newStatus = GameRules.computeStatus(prev)
        app.copy(
          game       = prev,
          status     = newStatus,
          lastMove   = None,
          highlights = Set.empty,
          message    = Some(TerminalView.info("Last move undone."))
        )

  // ── Resign ─────────────────────────────────────────────────────────────────

  private def handleResign(app: AppState): AppState =
    val loser  = app.game.activeColor
    val winner = if loser == Color.White then "Black" else "White"
    val resigned = app.game
    val newStatus = GameStatus.Checkmate(loser)  // reuse checkmate status for resign
    app.copy(
      status  = newStatus,
      message = Some(TerminalView.info(s"${loser} resigns. $winner wins!"))
    )

  // ── Draw offer ─────────────────────────────────────────────────────────────

  private def handleDraw(app: AppState): AppState =
    if app.drawOffer then
      // Second player accepted
      app.copy(
        status    = GameStatus.Draw("agreement"),
        drawOffer = false,
        message   = Some(TerminalView.info("Draw accepted."))
      )
    else
      app.copy(
        drawOffer = true,
        message   = Some(TerminalView.info(s"${app.game.activeColor} offers a draw. Type 'draw' to accept."))
      )
