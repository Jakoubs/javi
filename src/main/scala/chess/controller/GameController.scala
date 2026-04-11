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
  case ApplyMove(move: Move)
  case SelectSquare(pos: Option[Pos])
  case Flip
  case Undo
  case Resign
  case OfferDraw
  case NewGame
  case StartGame(clockMode: Option[(Long, Long)]) // (initialMillis, incrementMillis)
  case TimeExpired(loser: Color)
  case Help
  case Quit
  case AiMove
  case AiSuggest
  case AiTrain(n: Int)
  case ToggleAi(color: Color) // toggle AI for specific color
  case LoadPgn(pgn: String)
  case LoadFen(fen: String)
  case SwitchParser(parserType: String, variant: String)
  case StepBack
  case StepForward
  case FirstHistory
  case LastHistory
  case JumpToHistory(index: Int)
  case ShowPgn
  case ShowFen
  case AiProgress(msg: String)
  case Unknown(input: String)

// AppState ─────────────────────────────────────────────────────────────────

/** Everything the controller needs to know about the current UI state */
case class AppState(
  game:        GameState,
  status:      GameStatus,
  flipped:     Boolean         = false,
  highlights:  Set[Pos]        = Set.empty,
  selectedPos: Option[Pos]     = None,
  lastMove:    Option[Move]    = None,
  message:     Option[String]  = None,
  drawOffer:   Boolean         = false,
  running:     Boolean         = true,
  training:    Boolean         = false,
  trainingProgress: Option[String] = None,
  aiWhite:     Boolean         = false, // when true, AI plays for White
  aiBlack:     Boolean         = false, // when true, AI plays for Black
  clock:       Option[ClockState] = None,
  viewIndex:   Int             = 0,    // which history state we are viewing
  activePgnParser: String    = "regex",
  activeMoveParser: String   = "coordinate"
)

extension (s: AppState)
  def bottomPlayer: PlayerInfo =
    val info = s.game.materialInfo
    val c = s.clock.getOrElse(ClockState(0, 0, 0, None, false))
    if !s.flipped then
      PlayerInfo("White", info.blackCapturedSymbols, info.whiteAdvantage, c.whiteMillis)
    else
      PlayerInfo("Black", info.whiteCapturedSymbols, info.blackAdvantage, c.blackMillis)

  def topPlayer: PlayerInfo =
    val info = s.game.materialInfo
    val c = s.clock.getOrElse(ClockState(0, 0, 0, None, false))
    if !s.flipped then
      PlayerInfo("Black", info.whiteCapturedSymbols, info.blackAdvantage, c.blackMillis)
    else
      PlayerInfo("White", info.blackCapturedSymbols, info.whiteAdvantage, c.whiteMillis)

object AppState:
  def initial: AppState =
    val game   = GameState.initial
    val status = GameRules.computeStatus(game)
    AppState(game = game, status = status)

// ─── GameController ───────────────────────────────────────────────────────────

import chess.util.Observable

object GameController extends Observable[AppState]:
  import scala.concurrent.Future
  import scala.concurrent.ExecutionContext
  import java.util.concurrent.Executors

  // Unbounded cached thread pool: creates a new thread per game if needed.
  // Much more concurrent than the default ForkJoinPool (which caps at CPU cores).
  private given ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()))

  // ── Global State & Dispatch ────────────────────────────────────────────────
  
  var appState: AppState = AppState.initial

  def eval(cmd: Command): Unit = this.synchronized {
    appState = handleCommand(appState, cmd)
    notifyObservers(appState)
    
    // Auto AI move logic
    if appState.status == GameStatus.Playing || appState.status.isInstanceOf[GameStatus.Check] then
      val activeCol = appState.game.activeColor
      val isAiTurn = (activeCol == Color.White && appState.aiWhite) || 
                     (activeCol == Color.Black && appState.aiBlack)
                     
      if isAiTurn then
        // Calculate dynamic delay based on remaining time
        val remainingMs = appState.clock.map(_.activeMillis(activeCol)).getOrElse(100000L)
        val delayMs = if remainingMs < 1000 then 0
                      else if remainingMs < 5000 then 50
                      else if remainingMs < 10000 then 100
                      else 300
                      
        Future {
          if delayMs > 0 then Thread.sleep(delayMs)
          eval(Command.AiMove)
        }
  }

  // ── Command dispatch ───────────────────────────────────────────────────────

  def handleCommand(app: AppState, cmd: Command): AppState =
    import Command.*
    cmd match
      case ApplyMove(move) => handleMove(app, move)
      case SelectSquare(pos) => handleSelectSquare(app, pos)
      case Flip           => app.copy(highlights = Set.empty, selectedPos = None, message = None, flipped = !app.flipped)
      case Undo           => handleUndo(app)
      case Resign         => handleResign(app)
      case OfferDraw      => handleDraw(app)
      case NewGame        => 
        val init = AppState.initial
        init.copy(aiWhite = app.aiWhite, aiBlack = app.aiBlack, message = Some(TerminalView.info("New game started.")), viewIndex = 0)
      case StartGame(clock) => 
        val initClock = clock.map { case (init, inc) => ClockState(init, init, inc, None) }
        val init = AppState.initial
        init.copy(clock = initClock, aiWhite = app.aiWhite, aiBlack = app.aiBlack, message = Some(TerminalView.info("New game started.")), viewIndex = 0)
      case StepBack       => app.copy(viewIndex = Math.max(0, app.viewIndex - 1))
      case StepForward    => app.copy(viewIndex = Math.min(app.game.history.size, app.viewIndex + 1))
      case FirstHistory   => app.copy(viewIndex = 0)
      case LastHistory    => app.copy(viewIndex = app.game.history.size)
      case JumpToHistory(i) => app.copy(viewIndex = Math.max(0, Math.min(app.game.history.size, i)))
      case TimeExpired(l) => app.copy(
        status = GameStatus.Timeout(l), 
        clock = app.clock.map(_.copy(isActive = false)),
        message = Some(TerminalView.info(s"Time expired! ${l.opposite} wins."))
      )
      case Help           => app.copy(message = Some(TerminalView.helpText))
      case Quit           => app.copy(running = false, message = None)
      case AiMove         => handleAiMove(app)
      case AiSuggest      => handleAiSuggest(app)
      case AiTrain(n)     => handleAiTrain(app, n)
      case AiProgress(p)  => app.copy(trainingProgress = Some(p))
      case ToggleAi(c)    => handleToggleAi(app, c)
      case LoadPgn(pgn)   => 
        chess.util.Pgn.importPgn(pgn) match
          case scala.util.Success(state) => 
            app.copy(game = state, highlights = Set.empty, selectedPos = None, message = Some(TerminalView.success("PGN loaded successfully.")), viewIndex = state.history.size)
          case scala.util.Failure(exception) => 
            app.copy(message = Some(TerminalView.error(s"PGN Error: ${exception.getMessage}")), highlights = Set.empty)
      case LoadFen(fen)   =>
        chess.model.GameState.fromFen(fen) match
          case Right(state) => 
            app.copy(game = state, highlights = Set.empty, selectedPos = None, message = Some(TerminalView.success("FEN loaded successfully.")), viewIndex = state.history.size)
          case Left(errorMessage) => 
            app.copy(message = Some(TerminalView.error(s"FEN Error: $errorMessage")), highlights = Set.empty)
      case SwitchParser(pType, variant) =>
        import chess.util.parser.ParserRegistry
        pType.toLowerCase match
          case "pgn" =>
            ParserRegistry.pgnParsers.get(variant.toLowerCase) match
              case Some(parser) =>
                chess.util.parser.PgnParser.default = parser
                app.copy(activePgnParser = variant.toLowerCase, message = Some(TerminalView.success(s"PGN Parser switched to $variant")))
              case None =>
                app.copy(message = Some(TerminalView.error(s"Unknown PGN parser variant: $variant")))
          case "move" =>
            if ParserRegistry.moveParsers.contains(variant.toLowerCase) then
              app.copy(activeMoveParser = variant.toLowerCase, message = Some(TerminalView.success(s"Move Parser switched to $variant")))
            else
              app.copy(message = Some(TerminalView.error(s"Unknown Move parser variant: $variant")))
          case _ =>
            app.copy(message = Some(TerminalView.error(s"Unknown parser type: $pType")))
      case ShowPgn        => 
        val pgn = chess.util.Pgn.exportPgn(app.game)
        app.copy(message = Some(TerminalView.info(s"PGN:\n$pgn")))
      case ShowFen        => 
        val fen = app.game.toFen
        app.copy(message = Some(TerminalView.info(s"FEN: $fen")))
      case Unknown(msg)   => app.copy(message = Some(TerminalView.error(msg)), highlights = Set.empty)

  // ── Move handler ───────────────────────────────────────────────────────────

  private def validateMove(moveCoords: Move, state: GameState): Either[String, (Move, GameState)] =
    for {
      // 1. Unpack Option: Ist auf dem Startfeld eine Figur?
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

  private def handleMove(app: AppState, move: Move): AppState =
    val now = System.currentTimeMillis()
    
    // Time check first
    val timeExpiredState = app.clock.flatMap { c =>
      c.lastTickSysTime.flatMap { last =>
        val elapsed = now - last
        if c.activeMillis(app.game.activeColor) - elapsed <= 0 then
          Some(app.copy(
            status = GameStatus.Timeout(app.game.activeColor),
            clock = Some(c.copy(isActive = false)),
            message = Some(TerminalView.success(s"Timeout! ${app.game.activeColor.opposite} wins!"))
          ))
        else None
      }
    }
    
    if timeExpiredState.isDefined then return timeExpiredState.get

    // Game already over?
    val isGameOver = app.status match
      case GameStatus.Checkmate(_) | GameStatus.Stalemate | GameStatus.Draw(_) | GameStatus.Timeout(_) => true
      case _ => false

    if isGameOver then
      app.copy(message = Some(TerminalView.error("Game is over. Type 'new' to start again.")))
    else
      // Run Monadic Railway
      validateMove(move, app.game) match {
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
            case GameStatus.Timeout(loser)   => Some(TerminalView.success(s"Timeout! ${loser.opposite} wins!"))

          val nextClock = app.clock.map { c =>
            if newStatus != GameStatus.Playing then
              c.copy(isActive = false)
            else
              val elapsed = c.lastTickSysTime.map(now - _).getOrElse(0L)
              // First move: just set lastTickSysTime, don't deduct time
              val isFirstMove = c.lastTickSysTime.isEmpty && app.game.fullMoveNumber == 1 && app.game.activeColor == Color.White
              
              if app.game.activeColor == Color.White then
                c.copy(
                  whiteMillis = if isFirstMove then c.whiteMillis else c.whiteMillis - elapsed + c.incrementMillis,
                  lastTickSysTime = Some(now)
                )
              else
                c.copy(
                  blackMillis = c.blackMillis - elapsed + c.incrementMillis,
                  lastTickSysTime = Some(now)
                )
          }

          val nextApp = app.copy(
            game       = newGame,
            status     = newStatus,
            lastMove   = Some(move),
            highlights = Set.empty,
            selectedPos= None,
            message    = msg,
            drawOffer  = false,
            clock      = nextClock,
            viewIndex  = newGame.history.size
          )
          
          if nextApp.status != GameStatus.Playing then
            chess.ai.PassiveTrainer.train(nextApp.game, nextApp.status)
          
          nextApp

        case Left(errorMsg) =>
          app.copy(message = Some(TerminalView.error(errorMsg)), highlights = Set.empty, selectedPos = None)
      }

  private val MAX_MOVES_PER_GAME = 200

  private def simulateGame(): Unit =
    var gameApp = AppState.initial
    var moveCount = 0
    while gameApp.status == GameStatus.Playing && moveCount < MAX_MOVES_PER_GAME do
      val move = chess.ai.AiEngine.bestMove(gameApp.game, 2, epsilon = 0.1)
      move match
        case Some(m) => gameApp = handleMove(gameApp, m)
        case None    => gameApp = gameApp.copy(status = GameStatus.Draw("no legal moves"))
      moveCount += 1
    if gameApp.status == GameStatus.Playing then
      gameApp = gameApp.copy(status = GameStatus.Draw("max moves reached"))

  private def handleToggleAi(app: AppState, color: Color): AppState =
    if color == Color.White then
      val msg = if app.aiWhite then "AI White disabled." else "AI White enabled."
      app.copy(aiWhite = !app.aiWhite, message = Some(TerminalView.info(msg)))
    else
      val msg = if app.aiBlack then "AI Black disabled." else "AI Black enabled."
      app.copy(aiBlack = !app.aiBlack, message = Some(TerminalView.info(msg)))

  private def handleAiTrain(initialApp: AppState, numGames: Int): AppState =
    import java.util.concurrent.atomic.AtomicInteger

    val completed = AtomicInteger(0)
    val checkpointInterval = 50000 // user suggested 50,000 blocks

    // Launch games on the fixed thread pool
    val allGames = Future.traverse((1 to numGames).toList) { _ =>
      Future(simulateGame()).map { _ =>
        val done = completed.incrementAndGet()
        
        // Progress message
        if (done % 500 == 0 || done == numGames) then
          eval(Command.AiProgress(s"Training progress: $done/$numGames games completed."))
          
        // Checkpoint saving
        if (done % checkpointInterval == 0) then
          chess.ai.Evaluator.saveWeights()
          eval(Command.AiProgress(s"Checkpoint reached ($done games): Weights saved."))
      }
    }

    allGames.foreach { _ =>
      chess.ai.Evaluator.saveWeights()
      eval(Command.AiProgress(s"Training session completed! All $numGames games done."))
    }

    initialApp.copy(
      training = true,
      trainingProgress = Some("Initializing training..."),
      message = Some(TerminalView.success(s"Started parallel training: $numGames games. Checkpoints every $checkpointInterval."))
    )

  private def handleAiMove(app: AppState): AppState =
    if app.status != GameStatus.Playing && !app.status.isInstanceOf[GameStatus.Check] then
       app.copy(message = Some(TerminalView.error("Game is already over.")))
    else
      chess.ai.AiEngine.bestMove(app.game, 3) match
        case Some(move) => 
          handleMove(app, move)
        case None => 
          app.copy(message = Some(TerminalView.error("AI found no legal moves.")))

  private def handleAiSuggest(app: AppState): AppState =
    if app.status != GameStatus.Playing then
       app.copy(message = Some(TerminalView.error("Game is already over.")))
    else
      chess.ai.AiEngine.bestMove(app.game, 3) match
        case Some(move) => 
          app.copy(highlights = Set(move.from, move.to), message = Some(TerminalView.info(s"AI suggests: ${move.toInputString}")))
        case None => 
          app.copy(message = Some(TerminalView.error("AI found no legal moves.")))

  // ── Show legal moves for a square ─────────────────────────────────────────

  private def handleSelectSquare(app: AppState, optPos: Option[Pos]): AppState =
    optPos match
      case None =>
        app.copy(selectedPos = None, highlights = Set.empty, message = None)
      case Some(pos) =>
        app.game.board.get(pos) match
          case None =>
            app.copy(message = Some(TerminalView.error(s"No piece on ${pos.toAlgebraic}.")), highlights = Set.empty, selectedPos = None)
          case Some(piece) if piece.color != app.game.activeColor =>
            app.copy(message = Some(TerminalView.error("That is not your piece.")), highlights = Set.empty, selectedPos = None)
          case Some(_) =>
            val targets = MoveGenerator.legalMovesFrom(app.game, pos).map(_.to).toSet
            if targets.isEmpty then
              app.copy(message = Some(TerminalView.info("No legal moves for that piece.")), highlights = Set.empty, selectedPos = Some(pos))
            else
              app.copy(highlights = targets, selectedPos = Some(pos), message = None)

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
          selectedPos= None,
          message    = Some(TerminalView.info("Last move undone.")),
          viewIndex  = prev.history.size
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
