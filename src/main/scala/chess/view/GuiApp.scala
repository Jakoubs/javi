package chess.view

import chess.controller.{Command, CommandParser, CoreState, GameSession}
import chess.model.*

import scala.swing.*
import scala.swing.event.*

object GuiApp:

  def start(session: GameSession): Unit =
    Swing.onEDT {
      val ui = new GuiUi(session)
      ui.open()
    }

final class GuiUi(session: GameSession):

  private val boardButtons: Array[Array[Button]] =
    Array.tabulate(8, 8)((_, _) => new Button(""))

  private var core: CoreState = session.snapshot()
  private var flipped: Boolean = false
  private var selected: Option[Pos] = None
  private var mainFrame: MainFrame | Null = null
  private var highlights: Set[Pos] = Set.empty

  private val statusLabel = new Label("")
  private val messageArea = new TextArea:
    editable = false
    lineWrap = true
    wordWrap = true
    rows = 3

  private val moveField = new TextField(columns = 10)

  private val frame: MainFrame =
    val f = new MainFrame:
      title = "Chess"
      contents = buildUi()
      minimumSize = new Dimension(780, 720)
      centerOnScreen()
      refreshUi()
    mainFrame = f
    f

  session.addListener { s =>
    core = s
    Swing.onEDT {
      refreshUi()
    }
  }

  def open(): Unit =
    frame.visible = true

  private def buildUi(): Component =
    val boardGrid = new GridPanel(8, 8):
      for r <- 0 until 8 do
        for c <- 0 until 8 do
          val btn = boardButtons(r)(c)
          btn.focusable = false
          btn.font = new Font("Dialog", java.awt.Font.PLAIN, 28)
          btn.preferredSize = new Dimension(70, 70)
          listenTo(btn)
          reactions += { case ButtonClicked(`btn`) => onUiSquareClicked(uiCol = c, uiRow = r) }
          contents += btn

    val controls = new FlowPanel(FlowPanel.Alignment.Left)(
      new Button(Action("New") { dispatchCore(Command.NewGame) }),
      new Button(Action("Undo") { dispatchCore(Command.Undo) }),
      new Button(Action("Flip") { flipped = !flipped; highlights = Set.empty; refreshUi() }),
      new Button(Action("Draw") { dispatchCore(Command.OfferDraw) }),
      new Button(Action("Resign") { dispatchCore(Command.Resign) }),
      new Button(Action("Help") { showHelp() })
    )

    val movePanel = new FlowPanel(FlowPanel.Alignment.Left)(
      new Label("Move:"),
      moveField,
      new Button(Action("Play") { onMoveEntered() })
    )

    val rightPanel = new BorderPanel:
      layout(new Label("Info")) = BorderPanel.Position.North
      layout(new ScrollPane(messageArea)) = BorderPanel.Position.Center
      layout(movePanel) = BorderPanel.Position.South
      preferredSize = new Dimension(260, 0)

    val leftPanel = new BorderPanel:
      layout(controls) = BorderPanel.Position.North
      layout(boardGrid) = BorderPanel.Position.Center
      layout(statusLabel) = BorderPanel.Position.South

    new BorderPanel:
      border = Swing.EmptyBorder(10, 10, 10, 10)
      layout(leftPanel) = BorderPanel.Position.Center
      layout(rightPanel) = BorderPanel.Position.East

  private def onUiSquareClicked(uiCol: Int, uiRow: Int): Unit =
    val (col, row) =
      if !flipped then (uiCol, 7 - uiRow) else (7 - uiCol, uiRow)
    val pos = Pos(col, row)
    selected match
      case None =>
        core.game.board.get(pos) match
          case Some(piece) if piece.color == core.game.activeColor =>
            selected = Some(pos)
            val targets = MoveGenerator.legalMovesFrom(core.game, pos).map(_.to).toSet
            highlights = targets
          case _ =>
            messageArea.text = "Select one of your pieces."
      case Some(from) =>
        if from == pos then
          selected = None
          highlights = Set.empty
        else
          val promoChoice = promotionIfNeeded(from, pos)
          promoChoice match
            case PromotionChoice.Cancelled =>
              messageArea.text = "Promotion cancelled."
            case PromotionChoice.NotNeeded =>
              dispatchCore(Command.MakeMove(Move(from, pos, None)))
            case PromotionChoice.Chosen(p) =>
              dispatchCore(Command.MakeMove(Move(from, pos, Some(p))))
    refreshUi()

  private enum PromotionChoice:
    case NotNeeded
    case Cancelled
    case Chosen(piece: PieceType)

  private def promotionIfNeeded(from: Pos, to: Pos): PromotionChoice =
    val candidates = MoveGenerator.legalMoves(core.game).filter(m => m.from == from && m.to == to)
    if candidates.exists(_.promotion.isDefined) then
      askPromotion().fold(PromotionChoice.Cancelled)(PromotionChoice.Chosen.apply)
    else PromotionChoice.NotNeeded

  private def askPromotion(): Option[PieceType] =
    val parent: Window | Null = mainFrame

    val choice = Dialog.showInput(
      parent = parent,
      message = "Choose promotion piece (q/r/b/n)",
      title = "Promotion",
      entries = Seq("q", "r", "b", "n"),
      initial = "q"
    )
    choice.map(_.toString.trim.toLowerCase).flatMap {
      case "q" => Some(PieceType.Queen)
      case "r" => Some(PieceType.Rook)
      case "b" => Some(PieceType.Bishop)
      case "n" => Some(PieceType.Knight)
      case _   => None
    }

  private def onMoveEntered(): Unit =
    val txt = moveField.text.trim
    if txt.nonEmpty then
      CommandParser.parse(txt) match
        case Command.Flip =>
          flipped = !flipped
          highlights = Set.empty
          refreshUi()
        case Command.ShowMoves(pos) =>
          val targets = MoveGenerator.legalMovesFrom(core.game, pos).map(_.to).toSet
          highlights = targets
          refreshUi()
        case other =>
          dispatchCore(other)
      moveField.text = ""
      refreshUi()

  private def showHelp(): Unit =
    val help =
      """Commands:
        |- e2e4 / e7e8q: move (with optional promotion q/r/b/n)
        |- moves e2: show legal moves from square
        |- flip, undo, resign, draw, new, help
        |GUI:
        |- click a piece, then click a target square
        |""".stripMargin
    messageArea.text = help

  private def dispatchCore(cmd: Command): Unit =
    selected = None
    highlights = Set.empty
    val msg = session.dispatch(cmd)
    msg.foreach(m => messageArea.text = m.trim)
    refreshUi()

  private def refreshUi(): Unit =
    val flippedNow = flipped
    for uiRow <- 0 until 8 do
      for uiCol <- 0 until 8 do
        val (col, row) =
          if !flippedNow then (uiCol, 7 - uiRow) else (7 - uiCol, uiRow)
        val pos = Pos(col, row)
        val btn = boardButtons(uiRow)(uiCol)
        val baseLight = ((col + row) % 2 == 1)
        val bgBase = if baseLight then new java.awt.Color(240, 217, 181) else new java.awt.Color(181, 136, 99)

        val isSelected = selected.contains(pos)
        val isHighlight = highlights.contains(pos)
        val isLastMove = core.lastMove.exists(m => m.from == pos || m.to == pos)

        val bg =
          if isSelected then new java.awt.Color(120, 170, 255)
          else if isHighlight then new java.awt.Color(150, 220, 150)
          else if isLastMove then new java.awt.Color(255, 230, 150)
          else bgBase

        btn.background = bg
        btn.opaque = true

        core.game.board.get(pos) match
          case Some(Piece(Color.White, pt)) =>
            btn.foreground = java.awt.Color.WHITE
            btn.text = Piece(Color.White, pt).symbol.toString
          case Some(Piece(Color.Black, pt)) =>
            // leicht aufgehelltes Schwarz für bessere Sichtbarkeit auf dunklen Feldern
            btn.foreground = new java.awt.Color(30, 30, 30)
            btn.text = Piece(Color.Black, pt).symbol.toString
          case None =>
            btn.text = ""

    statusLabel.text = statusText(core)

  private def statusText(core: CoreState): String =
    val turn = s"${core.game.activeColor} to move"
    val s = core.status match
      case GameStatus.Playing => turn
      case GameStatus.Check(c) => s"$c is in check — $turn"
      case GameStatus.Checkmate(loser) =>
        val winner = if loser == Color.White then "Black" else "White"
        s"Checkmate — $winner wins"
      case GameStatus.Stalemate => "Stalemate — draw"
      case GameStatus.Draw(reason) => s"Draw by $reason"
    s"Move ${core.game.fullMoveNumber} • $s"

