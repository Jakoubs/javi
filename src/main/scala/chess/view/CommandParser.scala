package chess.view

import chess.controller.{AppState, Command}
import chess.model.{Color, Pos}
import chess.util.parser.ParserRegistry
import scala.util.Try

/**
 * Shared command parser for all interfaces (TUI, REST API, etc.)
 */
object CommandParser:

  def parse(input: String, app: AppState): Command =
    val trimmed = input.trim.toLowerCase

    trimmed match
      case "flip"               => Command.Flip
      case "undo"               => Command.Undo
      case "resign"             => Command.Resign
      case "draw"               => Command.OfferDraw
      case "new" | "newgame"    => Command.NewGame
      case "help" | "?"         => Command.Help
      case "quit" | "exit" | "q"=> Command.Quit
      case "pgn"                => Command.ShowPgn
      case "fen"                => Command.ShowFen
      case "ai"                 => Command.AiMove
      case "ai w" | "ai white"  => Command.ToggleAi(Color.White)
      case "ai b" | "ai black"  => Command.ToggleAi(Color.Black)
      case "back"               => Command.StepBack
      case "forward"            => Command.StepForward
      case "first"              => Command.FirstHistory
      case "last"               => Command.LastHistory
      
      case s if s.startsWith("start ") || s.startsWith("time ") =>
        val args = s.split("\\s+").drop(1)
        args match
          case Array("none") | Array("unlimited") => Command.StartGame(None)
          case Array("bullet")  => Command.StartGame(Some(1 * 60 * 1000L, 0L))
          case Array("blitz")   => Command.StartGame(Some(3 * 60 * 1000L, 2 * 1000L))
          case Array("rapid")   => Command.StartGame(Some(10 * 60 * 1000L, 0L))
          case Array(init, inc) => 
            (init.toLongOption, inc.toLongOption) match
              case (Some(ti), Some(tn)) => Command.StartGame(Some(ti, tn))
              case _ => Command.Unknown(s"Invalid time arguments: $s")
          case _ => Command.Unknown(s"Invalid command format: $s")

      case s if s.startsWith("moves ") =>
        val posStr = s.drop(6).trim
        Pos.fromAlgebraic(posStr) match
          case Some(p) => Command.SelectSquare(Some(p))
          case None    => Command.Unknown(s"Invalid position: $posStr")

      case s if s.startsWith("train ") =>
        val nStr = s.drop(6).trim
        nStr.toIntOption match
          case Some(n) if n > 0 => Command.AiTrain(n)
          case _                => Command.Unknown(s"Invalid training count: $nStr")

      case s if s.startsWith("jump ") =>
        val idxStr = s.drop(5).trim
        idxStr.toIntOption match
          case Some(i) => Command.JumpToHistory(i)
          case _       => Command.Unknown(s"Invalid jump index: $idxStr")

      case s if s.startsWith("parser ") =>
        val parts = s.drop(7).trim.split("\\s+").filter(_.nonEmpty)
        if parts.length == 2 then 
          Command.SwitchParser(parts(0), parts(1))
        else 
          Command.Unknown("Usage: parser <type> <variant> (e.g. parser pgn fast)")

      case s if s.length >= 2 =>
        // 1. Try raw algebraic coordinates (e.g. "e2") for square selection
        Pos.fromAlgebraic(s) match
          case Some(pos) => Command.SelectSquare(Some(pos))
          case None =>
            // 2. Try primary move parser, then fallback to others
            val primaryParser = ParserRegistry.moveParsers.getOrElse(app.activeMoveParser, chess.util.parser.CoordinateMoveParser)
            
            primaryParser.parse(s, app.game) match
              case scala.util.Success(move) => Command.ApplyMove(move)
              case scala.util.Failure(_)    => 
                val others = ParserRegistry.moveParsers.values.filter(_ != primaryParser)
                others.view.flatMap(p => p.parse(s, app.game).toOption).headOption match
                  case Some(move) => Command.ApplyMove(move)
                  case None =>
                    if s.length >= 4 || app.activeMoveParser == "san" then
                      Command.Unknown(s"Invalid move format: $s")
                    else
                      Command.Unknown(s"Unknown command or invalid move: $s")

      case s =>
        Command.Unknown(s"Unknown command: $s")
