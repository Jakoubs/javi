package chess.view

import chess.model.*

object TerminalView:

  // ANSI colour codes
  private val RESET = "\u001b[0m"
  private val BOLD = "\u001b[1m"
  private val DIM = "\u001b[2m"

  private val FG_WHITE = "\u001b[38;5;231m"
  private val FG_BLACK = "\u001b[38;5;16m"
  private val FG_GRAY = "\u001b[37m"
  private val FG_YELLOW = "\u001b[33m"
  private val FG_CYAN = "\u001b[36m"
  private val FG_GREEN = "\u001b[32m"
  private val FG_RED = "\u001b[31m"

  private val BG_LIGHT = "\u001b[48;5;180m"  // warm beige (light squares)
  private val BG_DARK = "\u001b[48;5;94m"    // brown (dark squares)
  private val BG_YELLOW = "\u001b[48;5;220m" // last move highlight
  private val BG_GREEN = "\u001b[48;5;64m"   // legal move dot
  private val BG_RED = "\u001b[48;5;124m"     // king in check

  def render(
      state: GameState,
      status: GameStatus,
      highlights: Set[Pos] = Set.empty, // legal-move targets
      lastMove: Option[Move] = None,
      flipped: Boolean = false
  ): String =
    val sb = new StringBuilder
    sb.append(header(state, status))
    sb.append("\n")
    sb.append(board(state, status, highlights, lastMove, flipped))
    sb.append(footer(state, status))
    sb.toString

  private def header(state: GameState, status: GameStatus): String =
    val top = "Black"
    val bottom = "White"

    val checkStr = status match
      case GameStatus.Check(_) => s" ${FG_RED}${BOLD}CHECK!${RESET}"
      case GameStatus.Checkmate(_) => s" ${FG_RED}${BOLD}CHECKMATE${RESET}"
      case _ => ""

    val moveStr = s"${DIM}Move ${state.fullMoveNumber}${RESET}"
    s"  $FG_CYAN$BOLD$top$RESET  $moveStr$checkStr\n"

  private def board(
      state: GameState,
      status: GameStatus,
      highlights: Set[Pos],
      lastMove: Option[Move],
      flipped: Boolean
  ): String =
    val lastMovePosSet: Set[Pos] =
      lastMove.map(m => Set(m.from, m.to)).getOrElse(Set.empty)

    val rows: Iterable[Int] = if flipped then 0 to 7 else (7 to 0 by -1)
    val cols: List[Int] = if flipped then (7 to 0 by -1).toList else (0 to 7).toList

    val sb = new StringBuilder

    rows.foreach { row =>
      sb.append(s"$FG_GRAY${BOLD} ${row + 1}  $RESET")

      cols.foreach { col =>
        val pos = Pos(col, row)
        val isLight = (col + row) % 2 == 1

        val inCheck =
          state.board.findKing(state.activeColor).contains(pos) &&
            (status match
              case GameStatus.Check(_) | GameStatus.Checkmate(_) => true
              case _ => false
            )

        val bg =
          if inCheck then BG_RED
          else if lastMovePosSet.contains(pos) then BG_YELLOW
          else if highlights.contains(pos) then BG_GREEN
          else if isLight then BG_LIGHT
          else BG_DARK

        val cell =
          state.board.get(pos) match
            case None =>
              if highlights.contains(pos) then s"$bg ${"•"}  $RESET"
              else s"$bg    $RESET"
            case Some(piece) =>
              val fg = if piece.color == Color.White then s"$FG_WHITE$BOLD" else FG_BLACK
              s"$bg$fg ${piece.symbol}  $RESET"

        sb.append(cell)
      }

      sb.append("\n")
    }

    // file labels: cells are 4 columns wide; prefix is 4 chars (" N  ")
    sb.append("    ")
    cols.foreach { col =>
      sb.append(s"$FG_GRAY${BOLD}  ${('a' + col).toChar} $RESET")
    }
    sb.append("\n")
    sb.toString

  private def footer(state: GameState, status: GameStatus): String =
    val turn = state.activeColor match
      case Color.White => s"${FG_WHITE}${BOLD}White${RESET}"
      case Color.Black => s"${FG_CYAN}${BOLD}Black${RESET}"

    val statusLine = status match
      case GameStatus.Playing =>
        s"\n  $turn to move"
      case GameStatus.Check(c) =>
        val col =
          if c == Color.White then s"${FG_WHITE}${BOLD}White${RESET}"
          else s"${FG_CYAN}${BOLD}Black${RESET}"
        s"\n  $col is in check — $turn to move"
      case GameStatus.Checkmate(loser) =>
        val winner =
          if loser == Color.White then s"${FG_CYAN}${BOLD}Black${RESET}"
          else s"${FG_WHITE}${BOLD}White${RESET}"
        s"\n  ${FG_RED}${BOLD}Checkmate!${RESET} $winner wins!"
      case GameStatus.Resigned(loser) =>
        val winner =
          if loser == Color.White then s"${FG_CYAN}${BOLD}Black${RESET}"
          else s"${FG_WHITE}${BOLD}White${RESET}"
        s"\n  ${FG_RED}${BOLD}Resignation!${RESET} $winner wins!"
      case GameStatus.Stalemate =>
        s"\n  ${FG_YELLOW}${BOLD}Stalemate!${RESET} The game is a draw. 🤝"
      case GameStatus.Timeout(loser) =>
        val winner = if loser == Color.White then s"${FG_CYAN}${BOLD}Black${RESET}" else s"${FG_WHITE}${BOLD}White${RESET}"
        s"\n  ${FG_RED}${BOLD}Timeout!${RESET} $winner wins on time!"
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
  ${BOLD}ai${RESET}            Let AI suggest/make a move
  ${BOLD}train [n]${RESET}      Run n games of AI self-play to train
  ${BOLD}pgn${RESET}           Export game as PGN
  ${BOLD}fen${RESET}           Export game as FEN
  ${BOLD}parser <t> <v>${RESET} Switch parser (e.g. parser move san)
  ${BOLD}help${RESET}          Show this help
  ${BOLD}quit${RESET}          Quit
  ${DIM}─────────────────────────────────────${RESET}
"""

  // ── Simple message helpers ────────────────────────────────────────────────

  def error(msg: String): String = s"  ${FG_RED}${BOLD}✗ $msg${RESET}\n"
  def success(msg: String): String = s"  ${FG_GREEN}${BOLD}✓ $msg${RESET}\n"
  def info(msg: String): String = s"  ${FG_YELLOW}$msg${RESET}\n"

  val prompt: String = s"  ${DIM}> ${RESET}"

  def clear(): Unit = print("\u001b[H\u001b[2J")

