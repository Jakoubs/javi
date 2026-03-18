package chess.view

import chess.model.*

// ─── TerminalView ─────────────────────────────────────────────────────────────

object TerminalView:

  // ANSI colour codes
  private val RESET      = "\u001b[0m"
  private val BOLD       = "\u001b[1m"
  private val DIM        = "\u001b[2m"
  private val UNDERLINE  = "\u001b[4m"

  private val FG_WHITE   = "\u001b[97m"
  private val FG_BLACK   = "\u001b[30m"
  private val FG_GRAY    = "\u001b[37m"
  private val FG_YELLOW  = "\u001b[33m"
  private val FG_CYAN    = "\u001b[36m"
  private val FG_GREEN   = "\u001b[32m"
  private val FG_RED     = "\u001b[31m"
  private val FG_MAGENTA = "\u001b[35m"

  private val BG_LIGHT   = "\u001b[48;5;180m"  // warm beige (light squares)
  private val BG_DARK    = "\u001b[48;5;94m"   // brown (dark squares)
  private val BG_YELLOW  = "\u001b[48;5;220m"  // last move highlight
  private val BG_GREEN   = "\u001b[48;5;64m"   // legal move dot
  private val BG_RED     = "\u001b[48;5;124m"  // king in check

  // ── Public render method ───────────────────────────────────────────────────

  def render(
              state:      GameState,
              status:     GameStatus,
              highlights: Set[Pos]   = Set.empty,   // legal-move targets
              lastMove:   Option[Move] = None,
              flipped:    Boolean      = false
            ): String =
    val sb = new StringBuilder
    sb.append(header(state, status))
    sb.append("\n")
    sb.append(board(state, status, highlights, lastMove, flipped))
    sb.append(footer(state, status))
    sb.toString

  // ── Header (player names, captured pieces placeholder) ───────────────────

  private def header(state: GameState, status: GameStatus): String =
    val (top, bottom) =
      ("Black", "White")

    val checkStr = status match
      case GameStatus.Check(_)     => s" ${FG_RED}${BOLD}CHECK!${RESET}"
      case GameStatus.Checkmate(_) => s" ${FG_RED}${BOLD}CHECKMATE${RESET}"
      case _                       => ""

    val moveStr = s"${DIM}Move ${state.fullMoveNumber}${RESET}"

    s"  $FG_CYAN$BOLD$top$RESET  $moveStr$checkStr\n"

  // ── Board ─────────────────────────────────────────────────────────────────

  private def board(
                     state:      GameState,
                     status:     GameStatus,
                     highlights: Set[Pos],
                     lastMove:   Option[Move],
                     flipped:    Boolean
                   ): String =
    val lastMovePosSet: Set[Pos] =
      lastMove.map(m => Set(m.from, m.to)).getOrElse(Set.empty)

    val rows = if flipped then 0 to 7 else (7 to 0 by -1)
    val cols = if flipped then (7 to 0 by -1).toList else (0 to 7).toList

    val sb = new StringBuilder

    rows.foreach { row =>
      // rank label: exactly 4 chars " N  "
      sb.append(s"$FG_GRAY${BOLD} ${row + 1}  $RESET")

      cols.foreach { col =>
        val pos    = Pos(col, row)
        val isLight = (col + row) % 2 == 1

        // Determine background
        val kingInCheckSquare =
          state.board.findKing(state.activeColor).contains(pos) &&
            (status match
              case GameStatus.Check(_) | GameStatus.Checkmate(_) => true
              case _                                             => false
            )

        val bg =
          if kingInCheckSquare then BG_RED
          else if lastMovePosSet.contains(pos) then BG_YELLOW
          else if highlights.contains(pos)     then BG_GREEN
          else if isLight                      then BG_LIGHT
          else                                      BG_DARK

      // Render in a fixed-width safe way (avoid unicode glyph width issues):
      // Every cell is exactly 4 terminal columns:
      //   piece : " P  " (or " p  ")
      //   empty : "    "
      //   dot   : " .  "
        val cell = state.board.get(pos) match
          case None =>
            if highlights.contains(pos) then s"$bg .  $RESET"
            else                             s"$bg    $RESET"
          case Some(piece) =>
            val fg = if piece.color == Color.White then FG_WHITE else s"$FG_BLACK$BOLD"
            s"$bg$fg ${piece.letter}  $RESET"

        sb.append(cell)
      }
      sb.append("\n")
    }

    // file labels: rank-label prefix is 4 chars wide, each cell is 4 columns
    sb.append("    ")
    cols.foreach { col =>
      sb.append(s"$FG_GRAY${BOLD}  ${('a' + col).toChar} $RESET")
    }
    sb.append("\n")
    sb.toString

  // ── Footer (status message + prompt) ──────────────────────────────────────

  private def footer(state: GameState, status: GameStatus): String =
    val turn = state.activeColor match
      case Color.White => s"${FG_WHITE}${BOLD}White${RESET}"
      case Color.Black => s"${FG_CYAN}${BOLD}Black${RESET}"

    val statusLine = status match
      case GameStatus.Playing => s"\n  $turn to move"
      case GameStatus.Check(c) =>
        val col =
          if c == Color.White then s"${FG_WHITE}${BOLD}White${RESET}"
          else s"${FG_CYAN}${BOLD}Black${RESET}"
        s"\n  $col is in check — $turn to move"
      case GameStatus.Checkmate(loser) =>
        val winner =
          if loser == Color.White then s"${FG_CYAN}${BOLD}Black${RESET}"
          else s"${FG_WHITE}${BOLD}White${RESET}"
        s"\n  ${FG_RED}${BOLD}Checkmate!${RESET} $winner wins! 🏆"
      case GameStatus.Stalemate =>
        s"\n  ${FG_YELLOW}${BOLD}Stalemate!${RESET} The game is a draw. 🤝"
      case GameStatus.Draw(reason) =>
        s"\n  ${FG_YELLOW}${BOLD}Draw${RESET} by $reason. 🤝"

    statusLine + "\n"

  // ── Help text ─────────────────────────────────────────────────────────────

  val helpText: String =
    s"""
  ${BOLD}${FG_CYAN}Chess Commands${RESET}
  ${DIM}─────────────────────────────────────${RESET}
  ${BOLD}e2e4${RESET}          Move piece (from→to)
  ${BOLD}e7e8q${RESET}         Move with promotion (q/r/b/n)
  ${BOLD}moves e2${RESET}      Show legal moves for piece on e2
  ${BOLD}flip${RESET}          Flip board perspective
  ${BOLD}undo${RESET}          Undo last move
  ${BOLD}resign${RESET}        Resign the game
  ${BOLD}draw${RESET}          Offer / accept draw
  ${BOLD}new${RESET}           Start a new game
  ${BOLD}help${RESET}          Show this help
  ${BOLD}quit${RESET}          Quit
  ${DIM}─────────────────────────────────────${RESET}
"""

  // ── Simple message helpers ────────────────────────────────────────────────

  def error(msg: String): String   = s"  ${FG_RED}${BOLD}✗ $msg${RESET}\n"
  def success(msg: String): String = s"  ${FG_GREEN}${BOLD}✓ $msg${RESET}\n"
  def info(msg: String): String    = s"  ${FG_YELLOW}$msg${RESET}\n"

  val prompt: String = s"  ${DIM}> ${RESET}"

  def clear(): Unit = print("\u001b[H\u001b[2J")