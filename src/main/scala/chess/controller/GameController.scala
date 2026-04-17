package chess.controller

import chess.model.*


enum MessageType:
  case Info, Success, Error

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
  case SwitchBot(name: String)
  case SetAiTime(ms: Int)
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
  messageType: MessageType = MessageType.Info,
  drawOffer:   Boolean         = false,
  running:     Boolean         = true,
  training:    Boolean         = false,
  trainingProgress: Option[String] = None,
  aiWhite:     Boolean         = false, // when true, AI plays for White
  aiBlack:     Boolean         = false, // when true, AI plays for Black
  clock:       Option[ClockState] = None,
  viewIndex:   Int             = 0,    // which history state we are viewing
  activePgnParser: String    = "regex",
  activeMoveParser: String   = "coordinate",
  aiBot: String              = "alphabeta",
  aiThinkingTime: Int        = 5000
)

extension (s: AppState)
  def liveMillis(color: Color): Long =
    s.clock match
      case Some(c) if c.isActive && s.game.activeColor == color && c.lastTickSysTime.isDefined =>
        val elapsed = System.currentTimeMillis() - c.lastTickSysTime.get
        Math.max(0L, c.activeMillis(color) - elapsed)
      case Some(c) => c.activeMillis(color)
      case None => 0L


  def displayFen: String =
    val h = s.game.history :+ s.game
    h.lift(s.viewIndex).getOrElse(s.game).toFen

  def historyFen: List[String] =
    (s.game.history :+ s.game).map(_.toFen)

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

  def eval(cmd: Command): AppState = this.synchronized {
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
    
    appState
  }

  // ── Command dispatch ───────────────────────────────────────────────────────

  def handleCommand(app: AppState, cmd: Command): AppState =
    import Command.*
    cmd match
      case ApplyMove(move) => handleMove(app, move)
      case SelectSquare(pos) => handleSelectSquare(app, pos)
      case Flip           => app.copy(highlights = Set.empty, selectedPos = None, message = None, flipped = !app.flipped, messageType = MessageType.Info)
      case Undo           => handleUndo(app)
      case Resign         => handleResign(app)
      case OfferDraw      => handleDraw(app)
      case NewGame        => 
        val init = AppState.initial
        init.copy(aiWhite = app.aiWhite, aiBlack = app.aiBlack, message = Some("New game started."), viewIndex = 0, messageType = MessageType.Info)
      case StartGame(clock) => 
        val initClock = clock.map { case (init, inc) => ClockState(init, init, inc, None) }
        val init = AppState.initial
        init.copy(clock = initClock, aiWhite = app.aiWhite, aiBlack = app.aiBlack, message = Some("New game started."), viewIndex = 0, messageType = MessageType.Info)
      case StepBack       => app.copy(viewIndex = Math.max(0, app.viewIndex - 1), messageType = MessageType.Info)
      case StepForward    => app.copy(viewIndex = Math.min(app.game.history.size, app.viewIndex + 1), messageType = MessageType.Info)
      case FirstHistory   => app.copy(viewIndex = 0, messageType = MessageType.Info)
      case LastHistory    => app.copy(viewIndex = app.game.history.size, messageType = MessageType.Info)
      case JumpToHistory(i) => app.copy(viewIndex = Math.max(0, Math.min(app.game.history.size, i)), messageType = MessageType.Info)
      case TimeExpired(l) => app.copy(
        status = GameStatus.Timeout(l), 
        clock = app.clock.map(_.copy(isActive = false)),
        message = Some(s"Time expired! ${l.opposite} wins."),
        messageType = MessageType.Info
      )
      case Help           => app.copy(message = Some("HELP"), messageType = MessageType.Info)
      case Quit           => app.copy(running = false, message = None, messageType = MessageType.Info)
      case AiMove         => handleAiMove(app)
      case AiSuggest      => handleAiSuggest(app)
      case AiTrain(n)     => handleAiTrain(app, n)
      case AiProgress(p)  => app.copy(trainingProgress = Some(p), messageType = MessageType.Info)
      case ToggleAi(c)    => handleToggleAi(app, c)
      case LoadPgn(pgn)   => 
        chess.util.Pgn.importPgn(pgn) match
          case scala.util.Success(state) => 
            app.copy(game = state, highlights = Set.empty, selectedPos = None, message = Some("PGN loaded successfully."), viewIndex = state.history.size, messageType = MessageType.Success)
          case scala.util.Failure(exception) => 
            app.copy(message = Some(s"PGN Error: ${exception.getMessage}"), highlights = Set.empty, messageType = MessageType.Error)
      case LoadFen(fen)   =>
        chess.model.GameState.fromFen(fen) match
          case Right(state) => 
            app.copy(game = state, highlights = Set.empty, selectedPos = None, message = Some("FEN loaded successfully."), viewIndex = state.history.size, messageType = MessageType.Success)
          case Left(errorMessage) => 
            app.copy(message = Some(s"FEN Error: $errorMessage"), highlights = Set.empty, messageType = MessageType.Error)
      case SwitchParser(pType, variant) =>
        import chess.util.parser.ParserRegistry
        pType.toLowerCase match
          case "pgn" =>
            ParserRegistry.pgnParsers.get(variant.toLowerCase) match
              case Some(parser) =>
                chess.util.parser.PgnParser.default = parser
                app.copy(activePgnParser = variant.toLowerCase, message = Some(s"PGN Parser switched to $variant"), messageType = MessageType.Success)
              case None =>
                app.copy(message = Some(s"Unknown PGN parser variant: $variant"), messageType = MessageType.Error)
          case "move" =>
            if ParserRegistry.moveParsers.contains(variant.toLowerCase) then
              app.copy(activeMoveParser = variant.toLowerCase, message = Some(s"Move Parser switched to $variant"), messageType = MessageType.Success)
            else
              app.copy(message = Some(s"Unknown Move parser variant: $variant"), messageType = MessageType.Error)
          case _ =>
            app.copy(message = Some(s"Unknown parser type: $pType"), messageType = MessageType.Error)
      case ShowPgn        => 
        val pgn = chess.util.Pgn.exportPgn(app.game)
        app.copy(message = Some(s"PGN:\n$pgn"), messageType = MessageType.Info)
      case ShowFen        => 
        val fen = app.game.toFen
        app.copy(message = Some(s"FEN: $fen"), messageType = MessageType.Info)
      case SwitchBot(name) =>
        app.copy(aiBot = name.toLowerCase, message = Some(s"AI Bot switched to $name"), messageType = MessageType.Success)
      case SetAiTime(ms) =>
        app.copy(aiThinkingTime = ms, message = Some(s"AI thinking time set to ${ms}ms"), messageType = MessageType.Success)
      case Unknown(msg)   => app.copy(message = Some(msg), highlights = Set.empty, messageType = MessageType.Error)

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
            message = Some(s"Timeout! ${app.game.activeColor.opposite} wins!")
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
      app.copy(message = Some("Game is over. Type 'new' to start again."))
    else
      // Run Monadic Railway
      validateMove(move, app.game) match {
        case Right((move, newGame)) =>
          val newStatus = GameRules.computeStatus(newGame)
          val msg = newStatus match
            case GameStatus.Checkmate(loser)  =>
              val winner = if loser == Color.White then "Black" else "White"
              Some(s"Checkmate! $winner wins!")
            case GameStatus.Resigned(loser)  =>
              val winner = if loser == Color.White then "Black" else "White"
              Some(s"$loser resigned. $winner wins!")
            case GameStatus.Stalemate        => Some("Stalemate — draw!")
            case GameStatus.Draw(reason)     => Some(s"Draw by $reason.")
            case GameStatus.Check(_)         => Some("Check!")
            case GameStatus.Playing          => None
            case GameStatus.Timeout(loser)   => Some(s"Timeout! ${loser.opposite} wins!")

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
            viewIndex  = newGame.history.size,
            messageType = MessageType.Success
          )
          
          if nextApp.status != GameStatus.Playing then
            chess.ai.PassiveTrainer.train(nextApp.game, nextApp.status)
          
          nextApp

        case Left(errorMsg) =>
          app.copy(message = Some(errorMsg), highlights = Set.empty, selectedPos = None, messageType = MessageType.Error)
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
      app.copy(aiWhite = !app.aiWhite, message = Some(msg), messageType = MessageType.Info)
    else
      val msg = if app.aiBlack then "AI Black disabled." else "AI Black enabled."
      app.copy(aiBlack = !app.aiBlack, message = Some(msg), messageType = MessageType.Info)

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
      message = Some(s"Started parallel training: $numGames games. Checkpoints every $checkpointInterval."),
      messageType = MessageType.Success
    )

  private def handleAiMove(app: AppState): AppState =
    if app.status != GameStatus.Playing && !app.status.isInstanceOf[GameStatus.Check] then
       app.copy(message = Some("Game is already over."), messageType = MessageType.Error)
    else
      val moveOpt = app.aiBot match
        case "simple" => chess.ai.AiEngine.bestMove(app.game, 3)
        case _        => chess.ai.AlphaBetaAgent.bestMove(app.game, timeLimitFromClock(app))
      
      moveOpt match
        case Some(move) => 
          handleMove(app, move)
        case None => 
          app.copy(message = Some("AI found no legal moves."), messageType = MessageType.Error)

  private def handleAiSuggest(app: AppState): AppState =
    if app.status != GameStatus.Playing then
       app.copy(message = Some("Game is already over."), messageType = MessageType.Error)
    else
      val moveOpt = app.aiBot match
        case "simple" => chess.ai.AiEngine.bestMove(app.game, 3)
        case _        => chess.ai.AlphaBetaAgent.bestMove(app.game, timeLimitFromClock(app))

      moveOpt match
        case Some(move) => 
          app.copy(highlights = Set(move.from, move.to), message = Some(s"AI suggests: ${move.toInputString}"), messageType = MessageType.Info)
        case None => 
          app.copy(message = Some("AI found no legal moves."), messageType = MessageType.Error)

  private def timeLimitFromClock(app: AppState): Long =
    app.clock match
      case None => app.aiThinkingTime.toLong
      case Some(c) =>
        val remaining = c.activeMillis(app.game.activeColor)
        val limit = (remaining / 30) + (c.incrementMillis * 0.8).toLong
        Math.max(100L, Math.min(5000L, limit))

  // ── Show legal moves for a square ─────────────────────────────────────────

  private def handleSelectSquare(app: AppState, optPos: Option[Pos]): AppState =
    optPos match
      case None =>
        app.copy(selectedPos = None, highlights = Set.empty, message = None)
      case Some(pos) =>
        // 1. CLICK-TO-MOVE: If square is a current highlight, move there!
        if app.highlights.contains(pos) && app.selectedPos.isDefined then
          val move = chess.model.Move(app.selectedPos.get, pos)
          // Look for matching legal move (handles promotion detection)
          val legal = chess.model.MoveGenerator.legalMoves(app.game)
          legal.find(m => m.from == move.from && m.to == move.to) match
            case Some(m) => handleMove(app, m)
            case None    => app.copy(message = Some("Illegal move!"), messageType = MessageType.Error)
        else
          // 2. REGULAR SELECTION
          app.game.board.get(pos) match
            case None =>
              app.copy(selectedPos = None, highlights = Set.empty, message = Some(s"No piece on ${pos.toAlgebraic}"), messageType = MessageType.Info)
            case Some(piece) if piece.color != app.game.activeColor =>
              app.copy(message = Some("That is not your piece."), highlights = Set.empty, selectedPos = None, messageType = MessageType.Error)
            case Some(_) =>
              val targets = MoveGenerator.legalMovesFrom(app.game, pos).map(_.to).toSet
              if targets.isEmpty then
                app.copy(message = Some("No legal moves for that piece."), highlights = Set.empty, selectedPos = Some(pos), messageType = MessageType.Info)
              else
                app.copy(highlights = targets, selectedPos = Some(pos), message = None, messageType = MessageType.Info)

  // ── Undo ───────────────────────────────────────────────────────────────────

  private def handleUndo(app: AppState): AppState =
    app.game.history.lastOption match
      case None =>
        app.copy(message = Some("Nothing to undo."), messageType = MessageType.Error)
      case Some(prev) =>
        val newStatus = GameRules.computeStatus(prev)
        app.copy(
          game       = prev,
          status     = newStatus,
          lastMove   = None,
          highlights = Set.empty,
          selectedPos= None,
          message    = Some("Last move undone."),
          viewIndex  = prev.history.size,
          messageType = MessageType.Info
        )

  // ── Resign ─────────────────────────────────────────────────────────────────

  private def handleResign(app: AppState): AppState =
    val loser  = app.game.activeColor
    val winner = if loser == Color.White then "Black" else "White"
    val newStatus = GameStatus.Resigned(loser)
    app.copy(
      status  = newStatus,
      message = Some(s"${loser} resigns. $winner wins!"),
      messageType = MessageType.Info,
      clock = app.clock.map(_.copy(isActive = false))
    )

  // ── Draw offer ─────────────────────────────────────────────────────────────

  private def handleDraw(app: AppState): AppState =
    if app.drawOffer then
      // Second player accepted
      app.copy(
        status    = GameStatus.Draw("agreement"),
        drawOffer = false,
        message   = Some("Draw accepted."),
        messageType = MessageType.Info
      )
    else
      app.copy(
        drawOffer = true,
        message   = Some(s"${app.game.activeColor} offers a draw. Type 'draw' to accept."),
        messageType = MessageType.Info
      )
