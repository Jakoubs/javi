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
      case s if s.startsWith("moves ") =>
        val posStr = s.drop(6).trim
        Pos.fromAlgebraic(posStr) match
          case Some(pos) => Command.ShowMoves(pos)
          case None      => Command.Unknown(s"Invalid position: $posStr")
      case s if s.exists(_.isDigit) && s.length >= 4 => 
        Command.ProcessTurn(s)
      case s => 
        Command.Unknown(s"Unknown command or invalid move format: $s")

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
  game:       GameState,
  status:     GameStatus,
  flipped:    Boolean         = false,
  highlights: Set[Pos]        = Set.empty,
  lastMove:   Option[Move]    = None,
  message:    Option[String]  = None,   // one-shot message shown after next render
  drawOffer:  Boolean         = false,
  running:    Boolean         = true
)

object AppState:
  def initial: AppState =
    val game   = GameState.initial
    val status = GameRules.computeStatus(game)
    AppState(game = game, status = status)

// ─── GameController ───────────────────────────────────────────────────────────

object GameController:

  // ── Main loop ──────────────────────────────────────────────────────────────

  def run(console: ConsoleIO = StdConsoleIO): Unit =
    var app = AppState.initial
    renderFull(app, console)

    while app.running do
      console.print(TerminalView.prompt)
      console.readLine() match
        case Some(input) =>
          app = handleCommand(app, CommandParser.parse(input.trim))
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
      case NewGame        => AppState.initial.copy(message = Some(TerminalView.info("New game started.")))
      case Help           => app.copy(message = Some(TerminalView.helpText))
      case Quit           => app.copy(running = false, message = None)
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
    app.status match
      case GameStatus.Checkmate(_) | GameStatus.Stalemate | GameStatus.Draw(_) =>
        return app.copy(message = Some(TerminalView.error("Game is over. Type 'new' to start again.")))
      case _ => ()

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

        app.copy(
          game       = newGame,
          status     = newStatus,
          lastMove   = Some(move),
          highlights = Set.empty,
          message    = msg,
          drawOffer  = false
        )

      case Left(errorMsg) =>
        app.copy(message = Some(TerminalView.error(errorMsg)), highlights = Set.empty)
    }

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
