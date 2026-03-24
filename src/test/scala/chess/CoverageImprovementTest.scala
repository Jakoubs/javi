package chess

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import chess.model.*
import chess.controller.*

class 
CoverageImprovementTest extends AnyFunSuite with Matchers:

  private def stateFromFen(fen: String): GameState =
    GameState.fromFen(fen).fold(msg => fail(msg), identity)

  private def pos(square: String): Pos =
    Pos.fromAlgebraic(square).getOrElse(fail(s"Invalid square: $square"))

  test("Board helpers and initial setup expose expected pieces") {
    val board = Board.initial

    board.get(pos("a1")) shouldBe Some(Piece(Color.White, PieceType.Rook))
    board.get(pos("b1")) shouldBe Some(Piece(Color.White, PieceType.Knight))
    board.get(pos("c1")) shouldBe Some(Piece(Color.White, PieceType.Bishop))
    board.get(pos("d1")) shouldBe Some(Piece(Color.White, PieceType.Queen))
    board.get(pos("e1")) shouldBe Some(Piece(Color.White, PieceType.King))
    board.get(pos("f1")) shouldBe Some(Piece(Color.White, PieceType.Bishop))
    board.get(pos("g1")) shouldBe Some(Piece(Color.White, PieceType.Knight))
    board.get(pos("h1")) shouldBe Some(Piece(Color.White, PieceType.Rook))
    board.get(pos("a2")) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    board.get(pos("d8")) shouldBe Some(Piece(Color.Black, PieceType.Queen))
    board.get(pos("a8")) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    board.get(pos("b8")) shouldBe Some(Piece(Color.Black, PieceType.Knight))
    board.get(pos("c8")) shouldBe Some(Piece(Color.Black, PieceType.Bishop))
    board.get(pos("e8")) shouldBe Some(Piece(Color.Black, PieceType.King))
    board.get(pos("f8")) shouldBe Some(Piece(Color.Black, PieceType.Bishop))
    board.get(pos("g8")) shouldBe Some(Piece(Color.Black, PieceType.Knight))
    board.get(pos("h8")) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    board.get(pos("h7")) shouldBe Some(Piece(Color.Black, PieceType.Pawn))
    board.isOccupied(pos("b1")) shouldBe true
    board.isOccupiedBy(pos("b1"), Color.White) shouldBe true
    board.isOccupiedBy(pos("b1"), Color.Black) shouldBe false
    board.isEmpty(pos("e4")) shouldBe true
    board.findKing(Color.White) shouldBe Some(pos("e1"))
    board.findKing(Color.Black) shouldBe Some(pos("e8"))
    board.allPiecesOf(Color.White) should have length 16
    board.allPiecesOf(Color.Black) should have length 16
  }

  test("Board put remove and movePiece cover replacement and missing-source cases") {
    val piece = Piece(Color.White, PieceType.Bishop)
    val board = Board.empty.put(pos("c1"), piece)

    board.movePiece(pos("a1"), pos("a2")) shouldBe board
    board.movePiece(pos("c1"), pos("h6")).get(pos("h6")) shouldBe Some(piece)
    board.remove(pos("c1")).get(pos("c1")) shouldBe None
  }

  test("Board fromFenPlacement parses valid mixed ranks") {
    val board = Board.fromFenPlacement("r3k2r/pppq1ppp/2npbn2/4p3/2BPP3/2N2N2/PPP2PPP/R1BQ1RK1")
      .fold(msg => fail(msg), identity)

    board.get(pos("a8")) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    board.get(pos("e8")) shouldBe Some(Piece(Color.Black, PieceType.King))
    board.get(pos("c4")) shouldBe Some(Piece(Color.White, PieceType.Bishop))
    board.get(pos("g1")) shouldBe Some(Piece(Color.White, PieceType.King))
    board.get(pos("b1")) shouldBe None
  }

  test("Board fromFenPlacement parses fully populated and sparse rows") {
    val fullBoard = Board.fromFenPlacement("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
      .fold(msg => fail(msg), identity)
    fullBoard.get(pos("a8")) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    fullBoard.get(pos("h1")) shouldBe Some(Piece(Color.White, PieceType.Rook))

    val sparseBoard = Board.fromFenPlacement("8/3k4/8/2P5/8/8/4K3/8")
      .fold(msg => fail(msg), identity)
    sparseBoard.get(pos("d7")) shouldBe Some(Piece(Color.Black, PieceType.King))
    sparseBoard.get(pos("c5")) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    sparseBoard.get(pos("e2")) shouldBe Some(Piece(Color.White, PieceType.King))
    sparseBoard.toFenPlacement shouldBe "8/3k4/8/2P5/8/8/4K3/8"
  }

  test("Board toFenPlacement compresses leading middle and trailing empty squares") {
    val board = Board(Map(
      pos("b8") -> Piece(Color.Black, PieceType.King),
      pos("d5") -> Piece(Color.White, PieceType.Queen),
      pos("g2") -> Piece(Color.White, PieceType.Pawn)
    ))

    board.toFenPlacement shouldBe "1k6/8/8/3Q4/8/8/6P1/8"
  }

  test("GameState initial and parsing cover valid metadata branches") {
    GameState.initial.toFen shouldBe GameState.initialFen

    val state = stateFromFen("4k3/8/8/8/8/8/8/4K3 b - - 12 42")
    state.activeColor shouldBe Color.Black
    state.castlingRights shouldBe CastlingRights(false, false, false, false)
    state.enPassantTarget shouldBe None
    state.halfMoveClock shouldBe 12
    state.fullMoveNumber shouldBe 42

    val withEnPassant = stateFromFen("4k3/8/8/8/8/8/8/4K3 w KQkq c3 0 1")
    withEnPassant.enPassantTarget shouldBe Some(pos("c3"))
    withEnPassant.castlingRights.toFen shouldBe "KQkq"
  }

  test("GameState fromFen accepts black en-passant targets and preserves serialization") {
    val state = stateFromFen("8/8/8/8/3p4/8/8/4K2k b - d3 9 17")
    state.activeColor shouldBe Color.Black
    state.enPassantTarget shouldBe Some(pos("d3"))
    state.toFen shouldBe "8/8/8/8/3p4/8/8/4K2k b - d3 9 17"
  }

  test("GameState fromFen handles multiple valid metadata combinations") {
    val noRights = stateFromFen("8/8/8/8/8/8/8/K6k w - - 0 3")
    noRights.castlingRights.toFen shouldBe "-"
    noRights.activeColor shouldBe Color.White

    val blackToMove = stateFromFen("8/8/8/8/8/8/8/K6k b Kq - 4 9")
    blackToMove.castlingRights shouldBe CastlingRights(
      whiteKingSide = true,
      whiteQueenSide = false,
      blackKingSide = false,
      blackQueenSide = true
    )
    blackToMove.toFen shouldBe "8/8/8/8/8/8/8/K6k b Kq - 4 9"
  }

  test("CastlingRights helpers toggle individual flags") {
    CastlingRights().disableWhite shouldBe CastlingRights(false, false, true, true)
    CastlingRights().disableBlack shouldBe CastlingRights(true, true, false, false)
    CastlingRights().disableWhiteKingSide shouldBe CastlingRights(false, true, true, true)
    CastlingRights().disableWhiteQueenSide shouldBe CastlingRights(true, false, true, true)
    CastlingRights().disableBlackKingSide shouldBe CastlingRights(true, true, false, true)
    CastlingRights().disableBlackQueenSide shouldBe CastlingRights(true, true, true, false)
  }

  test("CastlingRights toFen covers each individual combination style") {
    CastlingRights(whiteKingSide = true, whiteQueenSide = false, blackKingSide = false, blackQueenSide = false).toFen shouldBe "K"
    CastlingRights(whiteKingSide = false, whiteQueenSide = true, blackKingSide = false, blackQueenSide = false).toFen shouldBe "Q"
    CastlingRights(whiteKingSide = false, whiteQueenSide = false, blackKingSide = true, blackQueenSide = false).toFen shouldBe "k"
    CastlingRights(whiteKingSide = false, whiteQueenSide = false, blackKingSide = false, blackQueenSide = true).toFen shouldBe "q"
    CastlingRights(whiteKingSide = true, whiteQueenSide = true, blackKingSide = false, blackQueenSide = false).toFen shouldBe "KQ"
    CastlingRights(whiteKingSide = false, whiteQueenSide = false, blackKingSide = true, blackQueenSide = true).toFen shouldBe "kq"
  }

  test("GameRules computeStatus covers check") {
    val check = stateFromFen("R6k/8/8/8/8/8/8/4K3 b - - 0 1")
    GameRules.computeStatus(check) shouldBe GameStatus.Check(Color.Black)
  }

  test("GameRules applyMove handles castling and en-passant capture state updates") {
    val castleState = stateFromFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
    val afterCastle = GameRules.applyMove(castleState, Move(pos("e1"), pos("g1")))
    afterCastle.board.get(pos("g1")) shouldBe Some(Piece(Color.White, PieceType.King))
    afterCastle.board.get(pos("f1")) shouldBe Some(Piece(Color.White, PieceType.Rook))
    afterCastle.board.get(pos("h1")) shouldBe None

    val enPassant = stateFromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
    val afterEnPassant = GameRules.applyMove(enPassant, Move(pos("e5"), pos("d6")))
    afterEnPassant.board.get(pos("d5")) shouldBe None
    afterEnPassant.board.get(pos("d6")) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    afterEnPassant.activeColor shouldBe Color.Black
  }

  test("GameRules applyMove updates black-side castling rights and corner captures") {
    val blackKing = stateFromFen("r3k2r/8/8/8/8/8/8/4K3 b kq - 0 1")
    val afterBlackCastle = GameRules.applyMove(blackKing, Move(pos("e8"), pos("c8")))
    afterBlackCastle.board.get(pos("c8")) shouldBe Some(Piece(Color.Black, PieceType.King))
    afterBlackCastle.board.get(pos("d8")) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    afterBlackCastle.castlingRights.blackKingSide shouldBe false
    afterBlackCastle.castlingRights.blackQueenSide shouldBe false

    val captureA1 = stateFromFen("4k3/8/8/8/8/8/q7/R3K3 b Q - 0 1")
    GameRules.applyMove(captureA1, Move(pos("a2"), pos("a1"))).castlingRights.whiteQueenSide shouldBe false

    val captureH1 = stateFromFen("4k3/8/8/8/8/8/7q/4K2R b K - 0 1")
    GameRules.applyMove(captureH1, Move(pos("h2"), pos("h1"))).castlingRights.whiteKingSide shouldBe false

    val captureA8 = stateFromFen("r3k3/Q7/8/8/8/8/8/4K3 w q - 0 1")
    GameRules.applyMove(captureA8, Move(pos("a7"), pos("a8"))).castlingRights.blackQueenSide shouldBe false
  }

  test("CommandParser accepts remaining aliases and promotion pieces") {
    CommandParser.parse("?") shouldBe Command.Help
    CommandParser.parse("q") shouldBe Command.Quit
    CommandParser.parse("A7A8R") shouldBe Command.MakeMove(Move(pos("a7"), pos("a8"), Some(PieceType.Rook)))
    CommandParser.parse("a7a8b") shouldBe Command.MakeMove(Move(pos("a7"), pos("a8"), Some(PieceType.Bishop)))
    CommandParser.parse("a7a8n") shouldBe Command.MakeMove(Move(pos("a7"), pos("a8"), Some(PieceType.Knight)))
    CommandParser.parse("   ") shouldBe a[Command.Unknown]
  }

  test("GameController exposes draw-offer and resign messaging for black to move") {
    val drawOffered = GameController.handleCommand(AppState.initial, Command.OfferDraw)
    drawOffered.drawOffer shouldBe true
    drawOffered.message.getOrElse(fail("Expected draw message")) should include("offers a draw")

    val blackToMove = AppState(
      game = stateFromFen("4k3/8/8/8/8/8/8/4K3 b - - 0 1"),
      status = GameStatus.Playing
    )
    val resigned = GameController.handleCommand(blackToMove, Command.Resign)
    resigned.message.getOrElse(fail("Expected resign message")) should include("Black resigns. White wins!")
  }

  test("GameController handleMove emits a check message") {
    val checkApp = AppState(
      game = stateFromFen("7k/8/8/8/8/8/8/R3K3 w - - 0 1"),
      status = GameStatus.Playing
    )
    val afterCheck = GameController.handleCommand(checkApp, Command.MakeMove(Move(pos("a1"), pos("a8"))))
    afterCheck.status shouldBe GameStatus.Check(Color.Black)
    afterCheck.message.getOrElse(fail("Expected check message")) should include("Check!")
  }

  test("MoveGenerator covers direct attacks and pinned-piece filtering") {
    val pawnAttack = Board(Map(
      pos("d5") -> Piece(Color.Black, PieceType.Pawn)
    ))
    MoveGenerator.isAttackedBy(pawnAttack, pos("e4"), Color.Black) shouldBe true

    val knightAttack = Board(Map(
      pos("f5") -> Piece(Color.Black, PieceType.Knight)
    ))
    MoveGenerator.isAttackedBy(knightAttack, pos("e3"), Color.Black) shouldBe true

    val kingAttack = Board(Map(
      pos("e5") -> Piece(Color.Black, PieceType.King)
    ))
    MoveGenerator.isAttackedBy(kingAttack, pos("e4"), Color.Black) shouldBe true

    val bishopAttack = Board(Map(
      pos("b1") -> Piece(Color.Black, PieceType.Bishop)
    ))
    MoveGenerator.isAttackedBy(bishopAttack, pos("e4"), Color.Black) shouldBe true

    val rookAttack = Board(Map(
      pos("e8") -> Piece(Color.Black, PieceType.Rook)
    ))
    MoveGenerator.isAttackedBy(rookAttack, pos("e4"), Color.Black) shouldBe true

    val whitePawnAttack = Board(Map(
      pos("d4") -> Piece(Color.White, PieceType.Pawn)
    ))
    MoveGenerator.isAttackedBy(whitePawnAttack, pos("e5"), Color.White) shouldBe true

    val whiteKingAttack = Board(Map(
      pos("d4") -> Piece(Color.White, PieceType.King)
    ))
    MoveGenerator.isAttackedBy(whiteKingAttack, pos("e5"), Color.White) shouldBe true

    val noAttack = Board(Map(
      pos("a1") -> Piece(Color.White, PieceType.King),
      pos("h8") -> Piece(Color.Black, PieceType.King)
    ))
    MoveGenerator.isAttackedBy(noAttack, pos("e4"), Color.White) shouldBe false
    MoveGenerator.isAttackedBy(noAttack, pos("e4"), Color.Black) shouldBe false

    val pinned = stateFromFen("4r2k/8/8/8/8/8/4B3/4K3 w - - 0 1")
    MoveGenerator.legalMovesFrom(pinned, pos("e2")) shouldBe empty
  }

  test("MoveGenerator covers blocked sliders, black promotion and black castling") {
    val blockedRook = stateFromFen("4k3/8/8/8/3PRp2/8/8/4K3 w - - 0 1")
    MoveGenerator.legalMovesFrom(blockedRook, pos("e4")).map(_.to) should contain(pos("f4"))
    MoveGenerator.legalMovesFrom(blockedRook, pos("e4")).map(_.to) should not contain pos("d4")
    MoveGenerator.legalMovesFrom(blockedRook, pos("e4")).map(_.to) should not contain pos("g4")

    val blackPromotion = stateFromFen("4k3/8/8/8/8/8/2p5/K6R b - - 0 1")
    val promotionMoves = MoveGenerator.legalMovesFrom(blackPromotion, pos("c2"))
    promotionMoves.map(_.to) should contain(pos("c1"))
    promotionMoves.flatMap(_.promotion).distinct should contain allOf (
      PieceType.Queen,
      PieceType.Rook,
      PieceType.Bishop,
      PieceType.Knight
    )

    val blackCastle = stateFromFen("r3k2r/8/8/8/8/8/8/4K3 b kq - 0 1")
    val blackKingMoves = MoveGenerator.legalMovesFrom(blackCastle, pos("e8")).map(_.to)
    blackKingMoves should contain(pos("g8"))
    blackKingMoves should contain(pos("c8"))
  }

  test("MoveGenerator rejects castling when transit squares are occupied or attacked") {
    val occupiedTransit = stateFromFen("r3k2r/8/8/8/8/8/8/4KBNR w K - 0 1")
    MoveGenerator.legalMovesFrom(occupiedTransit, pos("e1")).map(_.to) should not contain pos("g1")
  }

  test("MoveGenerator rejects castling while in check and tolerates positions without own king") {
    val checkedKing = stateFromFen("4k3/8/8/8/8/8/4r3/4K2R w K - 0 1")
    MoveGenerator.legalMovesFrom(checkedKing, pos("e1")).map(_.to) should not contain pos("g1")

    val noWhiteKing = stateFromFen("4k3/8/8/8/8/8/4P3/8 w - - 0 1")
    MoveGenerator.legalMoves(noWhiteKing).map(_.to) should contain allOf (pos("e3"), pos("e4"))

    val legalEnPassant = stateFromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
    MoveGenerator.legalMoves(legalEnPassant) should contain (Move(pos("e5"), pos("d6")))
  }
