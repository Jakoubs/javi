package chess.view

import scalafx.application.JFXApp3
import scalafx.scene.Scene
import scalafx.scene.control.{Button, TextArea, ScrollPane, TextInputDialog, Alert, MenuBar, Menu, MenuItem, SeparatorMenuItem}
import scalafx.scene.layout.{GridPane, StackPane, HBox, VBox, Priority}
import scalafx.stage.{Stage, Modality}
import scalafx.scene.paint.Color
import scalafx.scene.shape.Rectangle
import scalafx.scene.text.{Font, Text}
import scalafx.geometry.{Pos, Insets}
import scalafx.Includes.*
import scalafx.application.Platform
import scalafx.animation.AnimationTimer

import chess.controller.{AppState, GameStateResponse}
import chess.model.{PieceType}

object Gui extends JFXApp3:
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.Await
  import scala.concurrent.duration.*

  private lazy val client = JaviClient()
  private var lastState: Option[GameStateResponse] = None

  private val tileSize = 80.0
  private val lightColor = Color.web("#f0d9b5")
  private val darkColor  = Color.web("#b58863")
  private val highlightColor = Color.web("#829769")
  private val selectedColor  = Color.web("#3498db")
  private val lastMoveColor  = Color.web("#f1c40f")

  private val squares = Array.ofDim[StackPane](8, 8)
  private lazy val topClockText = new Text { font = Font.font("Monospaced", 24); fill = Color.Black }
  private lazy val botClockText = new Text { font = Font.font("Monospaced", 24); fill = Color.Black }
  private lazy val topCapturedText = new Text { font = Font.font("Arial", 20); fill = Color.Black }
  private lazy val botCapturedText = new Text { font = Font.font("Arial", 20); fill = Color.Black }
  
  private lazy val topAiBtn = new Button("💻 AI (Off)") { 
    onAction = _ => { client.sendCommand("ai black").foreach(_ => refresh()) }
    style = "-fx-background-color: #ecf0f1;"
  }
  private lazy val botAiBtn = new Button("💻 AI (Off)") { 
    onAction = _ => { client.sendCommand("ai white").foreach(_ => refresh()) }
    style = "-fx-background-color: #ecf0f1;"
  }

  private lazy val statusLabel = new Text { 
    font = Font.font("Arial", 16); fill = Color.web("#e67e22") 
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
  
  private var gameOverPopupShown: Boolean = false

  def formatTime(ms: Long): String =
    val totalSec = Math.max(0, ms) / 1000
    val mn = totalSec / 60
    val sc = totalSec % 60
    f"$mn%02d:$sc%02d"

  private def refresh(): Unit = 
    client.fetchState().foreach {
      case Right(res) => Platform.runLater { updateUi(res) }
      case Left(_) => 
    }

  private def updateUi(res: GameStateResponse): Unit =
    if !res.running then System.exit(0)
    
    val stateChanged = lastState.isEmpty || lastState.get != res
    lastState = Some(res)

    val isOver = res.status != "Playing" && !res.status.startsWith("Check(") && res.status.nonEmpty
    if (isOver && !gameOverPopupShown) {
      gameOverPopupShown = true
      showGameOverModal(res)
    } else if (!isOver) {
      gameOverPopupShown = false
    }

    if (stateChanged) {
      updateBoard(res, res.displayFen)
      updateCapturedPieces(res)
      updateMoveHistory(res)
      
      statusLabel.text = res.message.getOrElse("")
      botAiBtn.text = if res.aiWhite then "💻 AI (White)" else "💻 AI (Off)"
      topAiBtn.text = if res.aiBlack then "💻 AI (Black)" else "💻 AI (Off)"
      
      botAiBtn.style = if res.aiWhite then "-fx-background-color: #27ae60; -fx-text-fill: white;" else "-fx-background-color: #ecf0f1; -fx-text-fill: black;"
      topAiBtn.style = if res.aiBlack then "-fx-background-color: #27ae60; -fx-text-fill: white;" else "-fx-background-color: #ecf0f1; -fx-text-fill: black;"
    }

    res.clock match
      case Some(_) =>
        botClockText.text = formatTime(res.whiteLiveMillis)
        topClockText.text = formatTime(res.blackLiveMillis)
      case None =>
        botClockText.text = ""; topClockText.text = ""

  private def updateMoveHistory(res: GameStateResponse): Unit =
    val moves = res.historyMoves
    moveHistoryList.children = moves.grouped(2).zipWithIndex.map { case (pair, groupIdx) =>
      val moveNum = groupIdx + 1
      new HBox {
        spacing = 10; padding = Insets(2, 5, 2, 5); alignment = Pos.CenterLeft
        children = Seq(
          new Text(s"$moveNum.") { fill = Color.Gray; font = Font.font("Monospaced", 14) },
          createMoveLink(res, pair(0), groupIdx * 2 + 1),
          if pair.size > 1 then createMoveLink(res, pair(1), groupIdx * 2 + 2) else new Text("") { prefWidth = 60 }
        )
      }
    }.toList

    // Auto-scroll logic: scroll toward the end, but allow manual browsing
    if res.viewIndex == res.historyFen.size - 1 then
      Platform.runLater { historyScrollPane.setVvalue(1.0) }

  private def createMoveLink(res: GameStateResponse, label: String, index: Int): Button =
    new Button(label) {
      prefWidth = 60
      alignment = Pos.CenterLeft
      style = if res.viewIndex == index then 
                "-fx-background-color: #3498db; -fx-text-fill: white; -fx-padding: 2 5 2 5; -fx-font-weight: bold; -fx-background-radius: 4;"
              else 
                "-fx-background-color: transparent; -fx-text-fill: #ecf0f1; -fx-padding: 2 5 2 5; -fx-background-radius: 4;"
      onAction = _ => client.sendCommand(s"jump $index").foreach(_ => refresh())
    }

  override def start(): Unit =
    val menuBar = new MenuBar {
      menus = Seq(
        new Menu("Zeit") {
          items = Seq(
            new MenuItem("Unlimited") { onAction = _ => client.sendCommand("start").foreach(_ => refresh()) },
            new MenuItem("1|0 Bullet") { onAction = _ => client.sendCommand("start 60000 0").foreach(_ => refresh()) },
            new MenuItem("3|2 Blitz")  { onAction = _ => client.sendCommand("start 180000 2000").foreach(_ => refresh()) },
            new MenuItem("10|0 Rapid") { onAction = _ => client.sendCommand("start 600000 0").foreach(_ => refresh()) }
          )
        },
        new Menu("Import/Export") {
          items = Seq(
            new MenuItem("Import PGN") { onAction = _ => showPgnOfImportDialog() },
            new MenuItem("Import FEN") { onAction = _ => showFenOfImportDialog() },
            new SeparatorMenuItem(),
            new MenuItem("Export PGN") { onAction = _ => lastState.foreach(s => showPgnOfExportDialog(s.pgn)) },
            new MenuItem("Export FEN") { onAction = _ => lastState.foreach(s => showFenOfExportDialog(s.fen)) }
          )
        },
        new Menu("Parser") {
          items = Seq(
            new MenuItem("Regex") { onAction = _ => client.sendCommand("parser pgn regex").foreach(_ => refresh()) },
            new MenuItem("Fastparse") { onAction = _ => client.sendCommand("parser pgn fast").foreach(_ => refresh()) },
            new MenuItem("Combinator") { onAction = _ => client.sendCommand("parser pgn combinator").foreach(_ => refresh()) }
          )
        }
      )
    }

    val grid = new GridPane()
    for row <- 0 until 8 do
      for col <- 0 until 8 do
        val stack = new StackPane {
          alignment = Pos.Center
          children = Seq(
            new Rectangle { width = tileSize; height = tileSize },
            new Text { font = Font.font("Arial", 48); mouseTransparent = true }
          )
          onMouseClicked = _ => {
            lastState.foreach { state =>
              val pos = if state.flipped then chess.model.Pos(7 - col, row) else chess.model.Pos(col, 7 - row)
              client.sendCommand(pos.toAlgebraic).foreach(_ => refresh())
            }
          }
        }
        squares(row)(col) = stack
        grid.add(stack, col, row)

    val navBar = new HBox {
      spacing = 5; alignment = Pos.CenterLeft; padding = Insets(5); style = "-fx-background-color: #2c3e50;"
      children = Seq(
        new Button("<<") { onAction = _ => client.sendCommand("first").foreach(_ => refresh()) },
        new Button("<")  { onAction = _ => client.sendCommand("back").foreach(_ => refresh()) },
        new Button(">")  { onAction = _ => client.sendCommand("forward").foreach(_ => refresh()) },
        new Button(">>") { onAction = _ => client.sendCommand("last").foreach(_ => refresh()) }
      )
    }

    val sideMenu = new VBox {
      padding = Insets(20); spacing = 15; alignment = Pos.TopCenter; style = "-fx-background-color: #2c3e50;"
      minWidth = 200
      children = Seq(
        new Text { text = "CHESS MENU"; fill = Color.White; font = Font.font("Arial", scalafx.scene.text.FontWeight.Bold, 20) },
        new Button("Flip Board") { onAction = _ => client.sendCommand("flip").foreach(_ => refresh()) },
        new Button("Undo Move") { onAction = _ => client.sendCommand("undo").foreach(_ => refresh()) },
        new Button("Resign") { onAction = _ => client.sendCommand("resign").foreach(_ => refresh()) },
        new Button("New Game") { onAction = _ => client.sendCommand("new").foreach(_ => refresh()) },
        new Button("Quit") { onAction = _ => client.sendCommand("quit").foreach(_ => refresh()) }
      )
    }

    val boardArea = new VBox {
      spacing = 15; padding = Insets(15); alignment = Pos.Center; style = "-fx-background-color: #ecf0f1;"
      children = Seq(
        new HBox { spacing = 20; alignment = Pos.CenterLeft; children = Seq(topAiBtn, topClockText, topCapturedText) },
        grid, 
        new HBox { spacing = 20; alignment = Pos.CenterLeft; children = Seq(botAiBtn, botClockText, botCapturedText) },
        statusLabel
      )
    }

    val root = new VBox(menuBar, new HBox(new VBox(historyScrollPane, navBar) { VBox.setVgrow(historyScrollPane, Priority.Always) }, boardArea, sideMenu))

    stage = new JFXApp3.PrimaryStage { title = "Chess Client"; scene = new Scene(root); resizable = false }

    val pollThread = new Thread(() => {
      while (true) {
        refresh()
        Thread.sleep(200)
      }
    })
    pollThread.setDaemon(true)
    pollThread.start()

  private def showGameOverModal(res: GameStateResponse): Unit =
    val titleStr = if res.status.startsWith("Checkmate") then "Checkmate!"
                   else if res.status.startsWith("Resigned") then "Resignation"
                   else if res.status.startsWith("Timeout") then "Time Out!"
                   else if res.status == "Stalemate" then "Stalemate"
                   else if res.status.startsWith("Draw") then "Draw"
                   else "Game Over"
                   
    val resultStr = if res.status.contains("White") then "Black Wins"
                    else if res.status.contains("Black") then "White Wins"
                    else if res.status.startsWith("Draw") then "Game is a Draw"
                    else res.status
                    
    Platform.runLater {
      new Stage {
        title = titleStr
        initModality(Modality.ApplicationModal)
        initOwner(stage)
        scene = new Scene {
          fill = Color.web("#f8f9fa")
          root = new VBox {
            padding = Insets(30); spacing = 20; alignment = Pos.Center; minWidth = 300
            children = Seq(
              new Text(titleStr) { font = Font.font("Arial", scalafx.scene.text.FontWeight.Bold, 24); fill = Color.web("#2c3e50") },
              new VBox {
                alignment = Pos.Center; spacing = 5
                children = Seq(
                  new Text(resultStr) { font = Font.font("Arial", scalafx.scene.text.FontWeight.Bold, 18) },
                  new Text(res.message.getOrElse("")) { font = Font.font("Arial", 14); fill = Color.Gray }
                )
              },
              new HBox {
                spacing = 15; alignment = Pos.Center
                children = Seq(
                  new Button("New Game") { 
                    padding = Insets(10, 20, 10, 20); minWidth = 100
                    onAction = _ => { client.sendCommand("new").foreach(_ => { refresh(); Platform.runLater { close() } }) }
                    style = "-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;"
                  },
                  new Button("Quit") { 
                    padding = Insets(10, 20, 10, 20); minWidth = 100
                    onAction = _ => { client.sendCommand("quit").foreach(_ => { Platform.runLater { close() } }) }
                  }
                )
              }
            )
          }
        }
      }.showAndWait()
    }

  private def updateCapturedPieces(res: GameStateResponse): Unit =
    val m = res.materialInfo
    topCapturedText.text = m.whiteCapturedSymbols.mkString(" ") + (if (m.blackAdvantage > 0) s"   +${m.blackAdvantage}" else "")
    botCapturedText.text = m.blackCapturedSymbols.mkString(" ") + (if (m.whiteAdvantage > 0) s"   +${m.whiteAdvantage}" else "")

  private def updateBoard(res: GameStateResponse, fen: String): Unit =
    val board = chess.model.GameState.fromFen(fen).getOrElse(chess.model.GameState.initial).board
    for row <- 0 until 8 do
      for col <- 0 until 8 do
        val stack = squares(row)(col)
        val bgRect = stack.children(0).asInstanceOf[javafx.scene.shape.Rectangle]
        val textNode = stack.children(1).asInstanceOf[javafx.scene.text.Text]
        val pos = if res.flipped then chess.model.Pos(7 - col, row) else chess.model.Pos(col, 7 - row)
        val posStr = pos.toAlgebraic
        val isLight = (pos.col + pos.row) % 2 == 1
        
        val baseColor = if isLight then lightColor else darkColor
        val tileColor = if res.selectedPos.contains(posStr) then selectedColor
                        else if res.lastMove.exists(m => m.contains(posStr)) then lastMoveColor
                        else if res.highlights.contains(posStr) then highlightColor
                        else baseColor
        
        bgRect.setFill(tileColor)
        
        board.get(pos) match
          case Some(piece) =>
            textNode.setText(piece.symbol)
            textNode.setFill(if piece.color == chess.model.Color.White then Color.White else Color.Black)
            textNode.setStroke(if piece.color == chess.model.Color.White then Color.Black else Color.White)
            textNode.setStrokeWidth(1.0)
          case None => 
            if res.highlights.contains(pos.toAlgebraic) then 
              textNode.setText("•"); textNode.setFill(Color.Black.opacity(0.3))
            else 
              textNode.setText("")

  private def showPgnOfExportDialog(pgn: String): Unit = 
    Platform.runLater {
      val alert = new Alert(Alert.AlertType.Information) { title = "PGN Export"; headerText = "Copy the PGN below:"; resizable = true }
      alert.getDialogPane.setContent(new TextArea(pgn) { editable = false; wrapText = true; prefHeight = 400 })
      alert.showAndWait()
    }

  private def showPgnOfImportDialog(): Unit = 
    Platform.runLater {
      val dialog = new TextInputDialog() { title = "PGN Import"; headerText = "Paste PGN:" }
      dialog.showAndWait().foreach(pgn => client.sendCommand(s"load pgn $pgn").foreach(_ => refresh()))
    }

  private def showFenOfExportDialog(fen: String): Unit = 
    Platform.runLater {
      new TextInputDialog(fen) { title = "FEN Export"; headerText = "Current FEN:" }.showAndWait()
    }

  private def showFenOfImportDialog(): Unit = 
    Platform.runLater {
      val dialog = new TextInputDialog() { title = "FEN Import"; headerText = "Paste FEN:" }
      dialog.showAndWait().foreach(fen => client.sendCommand(s"load fen $fen").foreach(_ => refresh()))
    }
