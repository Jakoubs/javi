package chess.controller

import chess.model.*
import chess.view.TerminalView

// ─── Command ADT ─────────────────────────────────────────────────────────────

enum Command:
  case MakeMove(move: Move)
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

    val simpleCommands = Map(
      "flip" -> Command.Flip,
      "undo" -> Command.Undo,
      "resign" -> Command.Resign,
      "draw" -> Command.OfferDraw,
      "new" -> Command.NewGame,
      "newgame" -> Command.NewGame,
      "help" -> Command.Help,
      "?" -> Command.Help,
      "quit" -> Command.Quit,
      "exit" -> Command.Quit,
      "q" -> Command.Quit
    )

    simpleCommands.get(trimmed).getOrElse {
      if trimmed.startsWith("moves ") then
        val posStr = trimmed.drop(6).trim
        Pos.fromAlgebraic(posStr)
          .map(Command.ShowMoves.apply)
          .getOrElse(Command.Unknown(s"Invalid position: $posStr"))
      else
        parseMove(trimmed)
          .getOrElse(Command.Unknown(s"Unknown command: '$input'. Type 'help' for help."))
    }

  private def parseMove(s: String): Option[Command] =
    if s.length < 4 then None
    else
      for
        from  <- Pos.fromAlgebraic(s.take(2))
        to    <- Pos.fromAlgebraic(s.drop(2).take(2))
        promo = s.drop(4).headOption.flatMap(charToPromotion)
        _     <- if s.drop(4).headOption.isDefined && promo.isEmpty then None else Some(())
      yield Command.MakeMove(Move(from, to, promo))

  private def charToPromotion(c: Char): Option[PieceType] = c match
    case 'q' => Some(PieceType.Queen)
    case 'r' => Some(PieceType.Rook)
    case 'b' => Some(PieceType.Bishop)
    case 'n' => Some(PieceType.Knight)
    case _   => None

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

  def run(): Unit =
    @annotation.tailrec
    def loop(app: AppState): Unit =
      renderFull(app)
      if app.running then
        print(TerminalView.prompt)
        val input = scala.io.StdIn.readLine()
        if input != null then
          loop(handleCommand(app, CommandParser.parse(input.trim)))

    loop(AppState.initial)

  // ── Render ─────────────────────────────────────────────────────────────────

  private def renderFull(app: AppState): Unit =
    TerminalView.clear()
    print(TerminalView.render(
      state      = app.game,
      status     = app.status,
      highlights = app.highlights,
      lastMove   = app.lastMove,
      flipped    = app.flipped
    ))
    app.message.foreach(print)

  // ── Command dispatch ───────────────────────────────────────────────────────

  def handleCommand(app: AppState, cmd: Command): AppState =
    import Command.*
    cmd match
      case MakeMove(move) => handleMove(app, move)
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

  private def handleMove(app: AppState, move: Move): AppState =
    // Game already over?
    app.status match
      case GameStatus.Checkmate(_) | GameStatus.Stalemate | GameStatus.Draw(_) =>
        return app.copy(message = Some(TerminalView.error("Game is over. Type 'new' to start again.")))
      case _ => ()

    val legal = MoveGenerator.legalMoves(app.game)

    // Check legality
    if !legal.exists(m => m.from == move.from && m.to == move.to && m.promotion == move.promotion) then
      // Maybe pawn promotion needed?
      if legal.exists(m => m.from == move.from && m.to == move.to && m.promotion.isDefined) && move.promotion.isEmpty then
        return app.copy(message = Some(TerminalView.error("Pawn promotion required. Append q/r/b/n (e.g. e7e8q).")))
      else
        return app.copy(message = Some(TerminalView.error("Illegal move.")), highlights = Set.empty)

    // Apply move
    val newGame   = GameRules.applyMove(app.game, move)
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
