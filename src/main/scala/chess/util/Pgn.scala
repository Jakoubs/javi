package chess.util

import chess.model.*
import scala.util.Try

object Pgn:

  /**
   * Generates a standard PGN string for the given game state history.
   */
  def exportPgn(finalState: GameState, whitePlayer: String = "White", blackPlayer: String = "Black"): String =
    val states = finalState.history :+ finalState
    if states.size < 2 then return ""

    val sb = new StringBuilder
    sb.append(s"[Event \"Casual Game\"]\n")
    sb.append(s"[Site \"Javi Chess\"]\n")
    sb.append(s"[White \"$whitePlayer\"]\n")
    sb.append(s"[Black \"$blackPlayer\"]\n")
    
    val resultStr = GameRules.computeStatus(finalState) match
      case GameStatus.Checkmate(Color.Black) => "1-0"
      case GameStatus.Checkmate(Color.White) => "0-1"
      case GameStatus.Draw(_) | GameStatus.Stalemate => "1/2-1/2"
      case _ => "*"
    sb.append(s"[Result \"$resultStr\"]\n\n")

    var movesText = new StringBuilder
    for i <- 0 until states.size - 1 do
      val state = states(i)
      val nextState = states(i+1)
      val moveOpt = deduceMove(state, nextState)
      
      moveOpt.foreach { move =>
        if state.activeColor == Color.White then
          movesText.append(s"${state.fullMoveNumber}. ")
        
        val san = toSan(state, move, nextState)
        movesText.append(s"$san ")
      }

    movesText.append(resultStr)
    
    // Line wrapping for PGN (usually 80 chars)
    sb.append(wrapText(movesText.toString(), 80))
    sb.append("\n")
    sb.toString()

  /**
   * Generates a simple, vertical list of moves without PGN headers.
   * Format: 
   * 1. e4
   * 1... e5
   */
  def exportMovesOnly(finalState: GameState): String =
    val states = finalState.history :+ finalState
    val sb = new StringBuilder
    
    for i <- 0 until states.size - 1 do
      val state = states(i)
      val nextState = states(i+1)
      deduceMove(state, nextState).foreach { move =>
        val san = toSan(state, move, nextState)
        if state.activeColor == Color.White then
          sb.append(s"${state.fullMoveNumber}. $san ")
        else
          sb.append(s"$san\n")
      }
    sb.toString()

  /**
   * Parses a basic PGN string and recreates the final GameState by playing out the moves.
   * Strips out tags, comments and move numbers, then brute-force maps SAN tokens.
   */
  def importPgn(pgn: String): Try[GameState] = Try {
    // 1. Remove comments { ... }
    val noComments = pgn.replaceAll("\\{.*?\\}", "")
    // 2. Remove tags [ ... ] 
    val noTags = noComments.replaceAll("\\[.*?\\]", "")
    // 3. Remove move numbers like 1. or 1...
    val noNumbers = noTags.replaceAll("\\d+\\.+", "")
    // 4. Remove results
    val noResults = noNumbers.replace("1-0", "").replace("0-1", "").replace("1/2-1/2", "").replace("*", "")
    
    // Split into SAN tokens
    val tokens = noResults.split("\\s+").filter(_.nonEmpty)
    
    var state = GameState.initial
    for token <- tokens do
      val legalMoves = MoveGenerator.legalMoves(state)
      // Wir suchen den Zug, dessen SAN-String mit dem Token übereinstimmt.
      // Falls + oder # im Token fehlen, tolerieren wir das durch einen puren Vergleich.
      val matchingMove = legalMoves.find { m =>
        val next = GameRules.applyMove(state, m)
        val san = toSan(state, m, next)
        san == token || san.replace("+", "").replace("#", "") == token.replace("+", "").replace("#", "")
      }
      
      matchingMove match
        case Some(m) =>
          state = GameRules.applyMove(state, m)
        case None =>
          throw new Exception(s"Invalid or illegal PGN move: $token")
          
    state
  }

  /**
   * Versucht herauszufinden, welcher Zug von State A zu State B geführt hat.
   */
  def deduceMove(fromState: GameState, toState: GameState): Option[Move] =
    val legal = MoveGenerator.legalMoves(fromState)
    legal.find { m => 
      // Ein schneller Vergleich des resultierenden Boards via FEN reicht idR aus,
      // da Züge das Board deterministisch verändern.
      GameRules.applyMove(fromState, m).board.toFenPlacement == toState.board.toFenPlacement
    }

  /**
   * Generiert den Standard Algebraic Notation (SAN) String für einen Zug.
   */
  def toSan(state: GameState, move: Move, nextState: GameState): String =
    val board = state.board
    val piece = board.get(move.from).get
    val isCapture = board.isOccupied(move.to) || (piece.pieceType == PieceType.Pawn && move.to.col != move.from.col)
    
    // Castling
    if piece.pieceType == PieceType.King && math.abs(move.to.col - move.from.col) == 2 then
      if move.to.col > move.from.col then return "O-O" else return "O-O-O"

    var san = piece.pieceType match
      case PieceType.Pawn => 
        if isCapture then s"${('a' + move.from.col).toChar}x" else ""
      case pt =>
        val baseLetter = piece.letter.toUpperCase
        // Disambiguation
        val legal = MoveGenerator.legalMoves(state)
        val similar = legal.filter(m => m.to == move.to && m.from != move.from && board.get(m.from).exists(_.pieceType == pt))
        if similar.isEmpty then baseLetter
        else if similar.forall(m => m.from.col != move.from.col) then s"$baseLetter${('a' + move.from.col).toChar}"
        else if similar.forall(m => m.from.row != move.from.row) then s"$baseLetter${move.from.row + 1}"
        else s"$baseLetter${move.from.toAlgebraic}"

    if isCapture && piece.pieceType != PieceType.Pawn then san += "x"
    
    san += move.to.toAlgebraic

    // Promotion
    move.promotion.foreach { pt =>
      san += s"=${Piece(Color.White, pt).letter.toUpperCase}"
    }

    // Check / Mate symbol
    val nextLegal = MoveGenerator.legalMoves(nextState)
    val isInCheck = MoveGenerator.isInCheck(nextState, nextState.activeColor)
    if nextLegal.isEmpty && isInCheck then san += "#"
    else if isInCheck then san += "+"

    san

  private def wrapText(text: String, len: Int): String =
    val words = text.split(" ")
    val sb = new StringBuilder
    var currentLineLen = 0
    for w <- words do
      if currentLineLen + w.length + 1 > len then
        sb.append("\n")
        currentLineLen = 0
      else if currentLineLen > 0 then
        sb.append(" ")
        currentLineLen += 1
      sb.append(w)
      currentLineLen += w.length
    sb.toString()
