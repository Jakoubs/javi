package chess.view

import scalafx.application.JFXApp3
import scalafx.scene.Scene
import scalafx.scene.control.{Button, ChoiceDialog, Separator, TextArea, ScrollPane, TextInputDialog, Alert, MenuBar, Menu, MenuItem, RadioMenuItem, ToggleGroup, SeparatorMenuItem}
import scalafx.scene.layout.{GridPane, StackPane, HBox, VBox, Priority}
import scalafx.scene.paint.Color
import scalafx.scene.shape.Rectangle
import scalafx.scene.text.{Font, Text}
import scalafx.geometry.{Pos, Insets}
import scalafx.Includes.*
import scalafx.application.Platform
import scalafx.animation.AnimationTimer

import chess.controller.{AppState, Command, GameController}
import chess.model.{Color => ChessColor, Pos => ChessPos, PieceType, capturedPieces, GameState, GameRules}
import chess.util.Observer

object Gui extends JFXApp3 with Observer[AppState]:
  
  private val tileSize = 80.0
  private val lightColor = Color.web("#f0d9b5")
  private val darkColor  = Color.web("#b58863")
  private val highlightColor = Color.web("#829769") // Greenish for selection/moves

  // Store references to panes to update them later
  private val squares = Array.ofDim[StackPane](8, 8)
  
  // UI Elements for Clock and Captured Pieces
  private lazy val topClockText = new Text { font = Font.font("Monospaced", 24); fill = Color.Black }
  private lazy val botClockText = new Text { font = Font.font("Monospaced", 24); fill = Color.Black }
  private lazy val topCapturedText = new Text { font = Font.font("Arial", 20); fill = Color.Black }
  private lazy val botCapturedText = new Text { font = Font.font("Arial", 20); fill = Color.Black }
  
  private lazy val topAiBtn = new Button("💻 AI (Off)") { 
    onAction = _ => GameController.eval(Command.ToggleAi(ChessColor.Black)); style = "-fx-background-color: #ecf0f1;"
  }
  private lazy val botAiBtn = new Button("💻 AI (Off)") { 
    onAction = _ => GameController.eval(Command.ToggleAi(ChessColor.White)); style = "-fx-background-color: #ecf0f1;"
  }

  private lazy val pgnArea = new TextArea {
    promptText = "PGN moves will appear here automatically..."
    wrapText = true
    prefRowCount = 10
    prefColumnCount = 25
    style = "-fx-font-family: 'Consolas', monospace;"
    editable = false
  }

  private lazy val moveHistoryList = new VBox {
    spacing = 5
    padding = Insets(10)
    style = "-fx-background-color: #34495e;"
  }

  private lazy val historyScrollPane = new ScrollPane {
    content = moveHistoryList
    fitToWidth = true
    prefWidth = 220
    style = "-fx-background-color: #2c3e50; -fx-background: #2c3e50;"
  }
  
  // State for user interaction
  private var gameOverPopupShown: Boolean = false

  def formatTime(ms: Long): String =
    val totalSec = Math.max(0, ms) / 1000
    val mn = totalSec / 60
    val sc = totalSec % 60
    f"$mn%02d:$sc%02d"

  override def update(state: AppState): Unit =
    // Update the UI on the JavaFX application thread
    Platform.runLater {
       if !state.running then
         Platform.exit()
         System.exit(0)
        else
          updateBoard(state)
          updateCapturedPieces(state)
          updateMoveHistory(state)
          
          // Auto-scroll to bottom
          Platform.runLater {
            historyScrollPane.vvalue = 1.0
          }
          
          botAiBtn.text = if state.aiWhite then "💻 AI (White)" else "💻 AI (Off)"
          topAiBtn.text = if state.aiBlack then "💻 AI (Black)" else "💻 AI (Off)"
    }

  private def updateMoveHistory(state: AppState): Unit =
    val history = state.game.history :+ state.game
    moveHistoryList.children = history.indices.flatMap { i =>
      if i == 0 then None // Initial state skip
      else if i % 2 != 0 then
        // Move pair starts
        val moveNum = (i + 1) / 2
        val whiteState = history(i)
        val blackState = if i + 1 < history.size then Some(history(i+1)) else None
        
        val row = new HBox {
          spacing = 10
          padding = Insets(2, 5, 2, 5)
          children = Seq(
            new Text(s"$moveNum.") { fill = Color.Gray; font = Font.font("Monospaced", 14) },
            createMoveLink(state, i, whiteState),
            blackState.map(s => createMoveLink(state, i + 1, s)).getOrElse(new Text("") { prefWidth = 50 })
          )
        }
        Some(row)
      else None
    }

  private def createMoveLink(state: AppState, index: Int, targetState: GameState): Button =
    val pgnMove = chess.util.Pgn.deduceMove(state.game.history.lift(index-1).getOrElse(GameState.initial), targetState)
      .map(m => chess.util.Pgn.toSan(state.game.history.lift(index-1).getOrElse(GameState.initial), m, targetState))
      .getOrElse("??")
      
    new Button(pgnMove) {
      style = if state.viewIndex == index then 
        "-fx-background-color: #3498db; -fx-text-fill: white; -fx-padding: 2 5 2 5;"
      else
        "-fx-background-color: transparent; -fx-text-fill: #ecf0f1; -fx-padding: 2 5 2 5;"
      onAction = _ => GameController.eval(Command.JumpToHistory(index))
    }

  override def start(): Unit =
    // Start game with default (Unlimited) first
    GameController.eval(Command.StartGame(None))

    // Subscribe to state changes
    GameController.addObserver(this)

    // Build the MenuBar for Time Control
    val menuBar = new MenuBar {
      menus = Seq(
        new Menu("Zeit") {
          items = Seq(
            new MenuItem("Unlimited") { onAction = _ => GameController.eval(Command.StartGame(None)) },
            new MenuItem("1|0 Bullet") { onAction = _ => GameController.eval(Command.StartGame(Some(1 * 60 * 1000L, 0L))) },
            new MenuItem("3|2 Blitz")  { onAction = _ => GameController.eval(Command.StartGame(Some(3 * 60 * 1000L, 2 * 1000L))) },
            new MenuItem("10|0 Rapid") { onAction = _ => GameController.eval(Command.StartGame(Some(10 * 60 * 1000L, 0L))) }
          )
        },
        new Menu("Import") {
          items = Seq(
            new MenuItem("PGN") { onAction = _ => showPgnImportDialog() },
            new MenuItem("FEN") { onAction = _ => showFenImportDialog() }
          )
        },
        new Menu("Parsers") {
          val pgnGroup = new ToggleGroup()
          val moveGroup = new ToggleGroup()
          
          items = Seq(
            new MenuItem("PGN Strategies") { disable = true },
            new RadioMenuItem("Regex (Default)") { toggleGroup = pgnGroup; selected = true; onAction = _ => GameController.eval(Command.SwitchParser("pgn", "regex")) },
            new RadioMenuItem("Combinators") { toggleGroup = pgnGroup; onAction = _ => GameController.eval(Command.SwitchParser("pgn", "combinator")) },
            new RadioMenuItem("FastParse") { toggleGroup = pgnGroup; onAction = _ => GameController.eval(Command.SwitchParser("pgn", "fast")) },
            new SeparatorMenuItem(),
            new MenuItem("Move Input Modes") { disable = true },
            new RadioMenuItem("Coordinate (e2e4)") { toggleGroup = moveGroup; selected = true; onAction = _ => GameController.eval(Command.SwitchParser("move", "coordinate")) },
            new RadioMenuItem("Algebraic (SAN)") { toggleGroup = moveGroup; onAction = _ => GameController.eval(Command.SwitchParser("move", "san")) }
          )
        }
      )
    }

    val grid = new GridPane()
    
    for row <- 0 until 8 do
      for col <- 0 until 8 do
        val pos = ChessPos(col, 7 - row) // Top of screen is row 7 (Black back rank)
        val isLight = (col + (7 - row)) % 2 == 1
        val bgColor = if isLight then lightColor else darkColor
        
        val bgRect = new Rectangle {
          width  = tileSize
          height = tileSize
          fill   = bgColor
        }
        
        val textNode = new Text {
          font = Font.font("Arial", 48)
        }
        
        val stack = new StackPane {
          alignment = Pos.Center
          children = Seq(bgRect, textNode)
          
          onMouseClicked = _ => handleSquareClick(pos)
        }
        
        squares(row)(col) = stack
        grid.add(stack, col, row)

    val navBar = new HBox {
      spacing = 5
      alignment = Pos.CenterLeft
      padding = Insets(5)
      style = "-fx-background-color: #2c3e50;"
      children = Seq(
        new Button("<<") { style = "-fx-min-width: 40px;"; onAction = _ => GameController.eval(Command.FirstHistory) },
        new Button("<")  { style = "-fx-min-width: 40px;"; onAction = _ => GameController.eval(Command.StepBack) },
        new Button(">")  { style = "-fx-min-width: 40px;"; onAction = _ => GameController.eval(Command.StepForward) },
        new Button(">>") { style = "-fx-min-width: 40px;"; onAction = _ => GameController.eval(Command.LastHistory) }
      )
    }

    val sideMenu = new VBox {
      padding = Insets(20)
      spacing = 15
      alignment = Pos.TopCenter
      style = "-fx-background-color: #2c3e50;"
      minWidth = 200
      
      val btnStyle = "-fx-font-size: 14pt; -fx-base: #ecf0f1; -fx-min-width: 160px;"
      
      children = Seq(
        new Text { text = "CHESS MENU"; fill = Color.White; font = Font.font("Arial", scalafx.scene.text.FontWeight.Bold, 20); margin = Insets(0, 0, 10, 0) },
        new Button("Flip Board") { style = btnStyle; onAction = _ => GameController.eval(Command.Flip) },
        new Button("Undo Move") { style = btnStyle; onAction = _ => GameController.eval(Command.Undo) },
        new Button("Resign") { style = btnStyle; onAction = _ => GameController.eval(Command.Resign) },
        new Button("Offer Draw") { style = btnStyle; onAction = _ => GameController.eval(Command.OfferDraw) },
        new Button("New Game") { style = btnStyle; onAction = _ => GameController.eval(Command.NewGame) },
        new Button("AI Suggestion") { style = btnStyle; onAction = _ => GameController.eval(Command.AiSuggest) },
        new Button("Train AI (100)") { style = btnStyle; onAction = _ => GameController.eval(Command.AiTrain(100)) },
        new Separator(),
        new Button("Quit") { style = "-fx-font-size: 14pt; -fx-base: #e74c3c; -fx-text-fill: white; -fx-min-width: 160px;"; onAction = _ => GameController.eval(Command.Quit) }
      )
    }

    val topBar = new HBox {
      spacing = 20
      alignment = Pos.CenterLeft
      children = Seq(topAiBtn, topClockText, topCapturedText)
    }
    val botBar = new HBox {
      spacing = 20
      alignment = Pos.CenterLeft
      children = Seq(botAiBtn, botClockText, botCapturedText)
    }
    
    val boardArea = new VBox {
      spacing = 15
      padding = Insets(15)
      alignment = Pos.Center
      style = "-fx-background-color: #ecf0f1;"
      children = Seq(topBar, grid, botBar)
    }

    val leftColumn = new VBox {
      children = Seq(historyScrollPane, navBar)
      VBox.setVgrow(historyScrollPane, Priority.Always)
    }
    val gameArea = new HBox(leftColumn, boardArea, sideMenu)
    val root = new VBox(menuBar, gameArea)

    stage = new JFXApp3.PrimaryStage {
      title = "Chess"
      scene = new Scene(root)
      resizable = false
    }

    // Timer for active countdown
    val timer = AnimationTimer { time =>
      val state = GameController.appState
      state.clock match
        case Some(clock) if clock.isActive =>
          val now = System.currentTimeMillis()
          val elapsed = clock.lastTickSysTime.map(now - _).getOrElse(0L)
          
          val wTime = if state.game.activeColor == ChessColor.White then
            Math.max(0L, clock.whiteMillis - elapsed) else clock.whiteMillis
          val bTime = if state.game.activeColor == ChessColor.Black then
            Math.max(0L, clock.blackMillis - elapsed) else clock.blackMillis
            
          botClockText.text = formatTime(wTime)
          topClockText.text = formatTime(bTime)
          
          if (wTime == 0 || bTime == 0) && state.status == chess.model.GameStatus.Playing then
            val loser = if wTime == 0 then ChessColor.White else ChessColor.Black
            GameController.eval(Command.TimeExpired(loser))
            
        case Some(clock) =>
          botClockText.text = formatTime(clock.whiteMillis)
          topClockText.text = formatTime(clock.blackMillis)
        case None =>
          botClockText.text = ""
          topClockText.text = ""
    }
    timer.start()

    // Initial render
    updateBoard(GameController.appState)
    updateCapturedPieces(GameController.appState)

  private def pieceValue(pt: PieceType): Int = pt match
    case PieceType.Pawn   => 1
    case PieceType.Knight => 3
    case PieceType.Bishop => 3
    case PieceType.Rook   => 5
    case PieceType.Queen  => 9
    case PieceType.King   => 0

  private def updateCapturedPieces(state: AppState): Unit =
    val capW = state.game.capturedPieces(ChessColor.White)
    val capB = state.game.capturedPieces(ChessColor.Black)
    
    val valW = capW.map(pieceValue).sum
    val valB = capB.map(pieceValue).sum
    
    val capturedWhiteStr = capW.map(pt => chess.model.Piece(ChessColor.White, pt).symbol).mkString(" ")
    val capturedBlackStr = capB.map(pt => chess.model.Piece(ChessColor.Black, pt).symbol).mkString(" ")
    
    // Captured White pieces are held by Black (top). Black's advantage = capW value - capB value
    val diffB = valW - valB
    val diffW = valB - valW
    
    topCapturedText.text = capturedWhiteStr + (if (diffB > 0) s"   +$diffB" else "")
    botCapturedText.text = capturedBlackStr + (if (diffW > 0) s"   +$diffW" else "")
    
    if state.aiBlack then
      topAiBtn.text = "💻 AI (On)"
      topAiBtn.style = "-fx-background-color: #27ae60; -fx-text-fill: white;"
    else
      topAiBtn.text = "💻 AI (Off)"
      topAiBtn.style = "-fx-background-color: #ecf0f1; -fx-text-fill: black;"
      
    if state.aiWhite then
      botAiBtn.text = "💻 AI (On)"
      botAiBtn.style = "-fx-background-color: #27ae60; -fx-text-fill: white;"
    else
      botAiBtn.text = "💻 AI (Off)"
      botAiBtn.style = "-fx-background-color: #ecf0f1; -fx-text-fill: black;"

  private def updateBoard(state: AppState): Unit =
    // Determine which state to render (history vs latest)
    val renderedState = if state.viewIndex == state.game.history.size then state.game 
                        else state.game.history(state.viewIndex)
    
    val board = renderedState.board
    val lastMovePosSet: Set[ChessPos] = if state.viewIndex == state.game.history.size then
      state.lastMove.map(m => Set(m.from, m.to)).getOrElse(Set.empty)
    else
      // For history states, we can highlight the move that led TO this state 
      // if it's not the initial state (index 0).
      if state.viewIndex > 0 then
        state.game.history.lift(state.viewIndex - 1).flatMap { prev =>
          chess.util.Pgn.deduceMove(prev, renderedState).map(m => Set(m.from, m.to))
        }.getOrElse(Set.empty)
      else Set.empty

    for row <- 0 until 8 do
      for col <- 0 until 8 do
        val pos = ChessPos(col, 7 - row)
        val stack = squares(row)(col)
        val bgRect = stack.children(0).asInstanceOf[javafx.scene.shape.Rectangle]
        val textNode = stack.children(1).asInstanceOf[javafx.scene.text.Text]
        
        val isLight = (col + (7 - row)) % 2 == 1
        val isInCheck = board.findKing(renderedState.activeColor).contains(pos) && 
                        (GameRules.computeStatus(renderedState) match
                          case chess.model.GameStatus.Check(_) | chess.model.GameStatus.Checkmate(_) => true
                          case _ => false
                        )
        
        val baseColor = if isLight then lightColor else darkColor
        bgRect.setFill(
          if isInCheck then Color.Red
          else if lastMovePosSet.contains(pos) then highlightColor.brighter()
          else if state.highlights.contains(pos) && state.viewIndex == state.game.history.size then highlightColor
          else baseColor
        )
        
        board.get(pos) match
          case Some(piece) =>
            textNode.setText(piece.symbol)
            textNode.setFill(if piece.color == ChessColor.White then Color.White else Color.Black)
            textNode.setStrokeWidth(1.0)
            textNode.setStroke(if piece.color == ChessColor.White then Color.Black else Color.White)
          case None =>
            if state.highlights.contains(pos) && state.viewIndex == state.game.history.size then
              textNode.setText("•")
              textNode.setFill(Color.Black.opacity(0.3))
              textNode.setStrokeWidth(0.0)
            else
              textNode.setText("")
              textNode.setStrokeWidth(0.0)
              
    // Game Over Popup logic (only for latest move)
    if state.viewIndex == state.game.history.size then
      state.status match
        case chess.model.GameStatus.Playing | chess.model.GameStatus.Check(_) =>
          gameOverPopupShown = false
        case other =>
          if !gameOverPopupShown then
            gameOverPopupShown = true
            val msg = other match
              case chess.model.GameStatus.Checkmate(loser) =>
                val winner = if loser == ChessColor.White then "Black" else "White"
                s"Checkmate! $winner wins the game."
              case chess.model.GameStatus.Timeout(loser) =>
                val winner = if loser == ChessColor.White then "Black" else "White"
                s"Timeout! $winner wins on time."
              case chess.model.GameStatus.Stalemate =>
                "Stalemate! The game is a draw."
              case chess.model.GameStatus.Draw(reason) =>
                s"Draw! Reason: $reason"
              case _ => "The game is over."
              
            val alert = new scalafx.scene.control.Alert(scalafx.scene.control.Alert.AlertType.Information) {
              title = "Game Over"
              headerText = "We have a result!"
              contentText = msg
            }
            alert.showAndWait()

  private def handleSquareClick(pos: ChessPos): Unit =
    val state = GameController.appState
    
    // Disable moves when browsing history
    if state.viewIndex == state.game.history.size then
      state.selectedPos match
        case None =>
          if state.game.board.isOccupiedBy(pos, state.game.activeColor) then
            GameController.eval(Command.SelectSquare(Some(pos)))
            
        case Some(from) =>
          if from == pos then
            GameController.eval(Command.SelectSquare(None))
          else if state.game.board.isOccupiedBy(pos, state.game.activeColor) then
            GameController.eval(Command.SelectSquare(Some(pos)))
          else
            val isPawn = state.game.board.get(from).exists(_.pieceType == PieceType.Pawn)
            val isPromotionRow = (pos.row == 0 || pos.row == 7)
            val promo = if isPawn && isPromotionRow then
              val choices = Seq("Queen ♛", "Rook ♜", "Bishop ♝", "Knight ♞")
              val dialog = new ChoiceDialog(defaultChoice = "Queen ♛", choices = choices) {
                title = "Pawn Promotion"
                headerText = "Choose the piece your pawn promotes to:"
              }
              dialog.showAndWait() match
                case Some("Rook ♜")   => Some(PieceType.Rook)
                case Some("Bishop ♝") => Some(PieceType.Bishop)
                case Some("Knight ♞") => Some(PieceType.Knight)
                case _                => Some(PieceType.Queen)
            else None
            
            GameController.eval(Command.ApplyMove(chess.model.Move(from, pos, promo)))
    else
      // Just jump to latest? Or do nothing?
      val alert = new Alert(Alert.AlertType.Warning) {
        title = "History View"
        headerText = "You are currently viewing history."
        contentText = "Go to the latest move (or click >>) to continue playing."
      }
      alert.showAndWait()

  private def showPgnExportDialog(game: GameState): Unit =
    val pgn = chess.util.Pgn.exportPgn(game)
    val alert = new Alert(Alert.AlertType.Information) {
      title = "PGN Export"
      headerText = "Copy the PGN below:"
      resizable = true
    }
    val textArea = new TextArea(pgn) {
      editable = false
      wrapText = true
      prefHeight = 400
    }
    alert.getDialogPane.setContent(textArea)
    alert.showAndWait()

  private def showPgnImportDialog(): Unit =
    val dialog = new TextInputDialog() {
      title = "PGN Import"
      headerText = "Paste PGN to load game history:"
    }
    val result = dialog.showAndWait()
    result.foreach(pgn => GameController.eval(Command.LoadPgn(pgn)))

  private def showFenExportDialog(game: GameState): Unit =
    val fen = game.toFen
    val dialog = new TextInputDialog(fen) {
      title = "FEN Export"
      headerText = "Current board FEN:"
    }
    dialog.showAndWait()

  private def showFenImportDialog(): Unit =
    val dialog = new TextInputDialog() {
      title = "FEN Import"
      headerText = "Paste your FEN string (position only or full format):"
    }
    val result = dialog.showAndWait()
    result.foreach(fen => GameController.eval(Command.LoadFen(fen)))
