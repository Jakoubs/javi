package chess.view

import scalafx.application.JFXApp3
import scalafx.scene.Scene
import scalafx.scene.layout.{GridPane, StackPane, HBox, VBox}
import scalafx.scene.control.{Button, ChoiceDialog}
import scalafx.scene.paint.Color
import scalafx.scene.shape.Rectangle
import scalafx.scene.text.{Font, Text}
import scalafx.geometry.{Pos, Insets}
import scalafx.Includes.*
import scalafx.application.Platform
import scalafx.animation.AnimationTimer

import chess.controller.{AppState, Command, GameController}
import chess.model.{Color => ChessColor, Pos => ChessPos, PieceType, capturedPieces}
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
  
  // State for user interaction
  private var selectedPos: Option[ChessPos] = None
  private var gameOverPopupShown: Boolean = false

  def formatTime(ms: Long): String =
    val totalSec = Math.max(0, ms) / 1000
    val mn = totalSec / 60
    val sc = totalSec % 60
    f"$mn%02d:$sc%02d"

  override def update(state: AppState): Unit =
    // Update the UI on the JavaFX application thread
    Platform.runLater {
       updateBoard(state)
       updateCapturedPieces(state)
    }

  override def start(): Unit =
    // Startup Popup
    val choices = Seq("Unlimited", "1|0 Bullet", "3|2 Blitz", "10|0 Rapid")
    val dialog = new ChoiceDialog(defaultChoice = "Unlimited", choices = choices) {
      title = "Chess Time Control"
      headerText = "Select your time control mode:"
    }
    
    val result = dialog.showAndWait()
    val clockMode = result match
      case Some("1|0 Bullet") => Some((1 * 60 * 1000L, 0L))
      case Some("3|2 Blitz")  => Some((3 * 60 * 1000L, 2 * 1000L))
      case Some("10|0 Rapid") => Some((10 * 60 * 1000L, 0L))
      case _                  => None
      
    // Start game with selected clock
    GameController.eval(Command.StartGame(clockMode))

    // Subscribe to state changes
    GameController.addObserver(this)
    
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

    val root = new HBox(sideMenu, boardArea)

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
    // Highlight logic
    val highlights = state.highlights
    
    for row <- 0 until 8 do
      for col <- 0 until 8 do
        val pos = ChessPos(col, 7 - row)
        val isLight = (col + (7 - row)) % 2 == 1
        
        val square = squares(row)(col)
        val bgRect = square.children(0).asInstanceOf[javafx.scene.shape.Rectangle]
        val textNode = square.children(1).asInstanceOf[javafx.scene.text.Text]
        
        // Colors
        if selectedPos.contains(pos) || highlights.contains(pos) then
          bgRect.setFill(highlightColor)
        else
          bgRect.setFill(if isLight then lightColor else darkColor)
        
        // Piece
        state.game.board.get(pos) match
          case Some(piece) =>
            textNode.setText(piece.symbol)
            textNode.setFill(if piece.color == ChessColor.White then Color.White else Color.Black)
            textNode.setStrokeWidth(1.0)
            textNode.setStroke(if piece.color == ChessColor.White then Color.Black else Color.White)
          case None =>
            if highlights.contains(pos) then
              textNode.setText("•")
              textNode.setFill(Color.Black.opacity(0.3))
              textNode.setStrokeWidth(0.0)
            else
              textNode.setText("")
              textNode.setStrokeWidth(0.0)
              
    // Game Over Popup logic
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
    
    selectedPos match
      case None =>
        // Select a piece
        if state.game.board.isOccupiedBy(pos, state.game.activeColor) then
          selectedPos = Some(pos)
          GameController.eval(Command.ShowMoves(pos))
          
      case Some(from) =>
        if from == pos then
          // Deselect
          selectedPos = None
          GameController.eval(Command.ProcessTurn("invalid")) // To clear highlights via normal error path or we could use another command
        else if state.game.board.isOccupiedBy(pos, state.game.activeColor) then
          // Select another of own pieces
          selectedPos = Some(pos)
          GameController.eval(Command.ShowMoves(pos))
        else
          // Attempt move
          val moveStr = s"${from.toAlgebraic}${pos.toAlgebraic}"
          
          // Promotion dialog
          val isPawn = state.game.board.get(from).exists(_.pieceType == chess.model.PieceType.Pawn)
          val isPromotionRow = (pos.row == 0 || pos.row == 7)
          val finalMoveStr = if isPawn && isPromotionRow then
            val choices = Seq("Queen ♛", "Rook ♜", "Bishop ♝", "Knight ♞")
            val dialog = new ChoiceDialog(defaultChoice = "Queen ♛", choices = choices) {
              title = "Pawn Promotion"
              headerText = "Choose the piece your pawn promotes to:"
            }
            val promo = dialog.showAndWait() match
              case Some("Rook ♜")   => "r"
              case Some("Bishop ♝") => "b"
              case Some("Knight ♞") => "n"
              case _                => "q"
            moveStr + promo
          else moveStr
          
          selectedPos = None
          GameController.eval(Command.ProcessTurn(finalMoveStr))
