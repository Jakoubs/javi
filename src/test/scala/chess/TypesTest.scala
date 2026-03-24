package chess

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import chess.model.*

class TypesTest extends AnyFunSuite with Matchers with ScalaCheckPropertyChecks:

  test("Color.opposite should return opposite color") {
    Color.White.opposite shouldBe Color.Black
    Color.Black.opposite shouldBe Color.White
  }

  test("Piece.symbol should return correct Unicode symbols") {
    val whiteKing = Piece(Color.White, PieceType.King)
    whiteKing.symbol shouldBe "♔"
    
    val blackQueen = Piece(Color.Black, PieceType.Queen)
    blackQueen.symbol shouldBe "♛"
    
    val whitePawn = Piece(Color.White, PieceType.Pawn)
    whitePawn.symbol shouldBe "♙"
    
    val blackPawn = Piece(Color.Black, PieceType.Pawn)
    blackPawn.symbol shouldBe "♟"
  }

  test("Piece.letter should return correct algebraic notation") {
    val whiteKing = Piece(Color.White, PieceType.King)
    whiteKing.letter shouldBe "K"
    
    val blackQueen = Piece(Color.Black, PieceType.Queen)
    blackQueen.letter shouldBe "q"
    
    val whitePawn = Piece(Color.White, PieceType.Pawn)
    whitePawn.letter shouldBe "P"
    
    val blackPawn = Piece(Color.Black, PieceType.Pawn)
    blackPawn.letter shouldBe "p"
  }

  test("Pos.isValid should validate board positions") {
    // Valid positions
    Pos(0, 0).isValid shouldBe true
    Pos(7, 7).isValid shouldBe true
    Pos(3, 4).isValid shouldBe true
    
    // Invalid positions
    Pos(-1, 0).isValid shouldBe false
    Pos(0, -1).isValid shouldBe false
    Pos(8, 0).isValid shouldBe false
    Pos(0, 8).isValid shouldBe false
    Pos(-1, -1).isValid shouldBe false
    Pos(8, 8).isValid shouldBe false
  }

  test("Pos.fromAlgebraic should parse valid notation") {
    Pos.fromAlgebraic("a1") shouldBe Some(Pos(0, 0))
    Pos.fromAlgebraic("h8") shouldBe Some(Pos(7, 7))
    Pos.fromAlgebraic("e4") shouldBe Some(Pos(4, 3))
    Pos.fromAlgebraic("d5") shouldBe Some(Pos(3, 4))
  }

  test("Pos.fromAlgebraic should reject invalid notation") {
    Pos.fromAlgebraic("a0") shouldBe None
    Pos.fromAlgebraic("a9") shouldBe None
    Pos.fromAlgebraic("i1") shouldBe None
    Pos.fromAlgebraic("z9") shouldBe None
    Pos.fromAlgebraic("") shouldBe None
    Pos.fromAlgebraic("a") shouldBe None
    Pos.fromAlgebraic("1") shouldBe None
  }

  test("Pos.toAlgebraic should convert back to algebraic notation") {
    Pos(0, 0).toAlgebraic shouldBe "a1"
    Pos(7, 7).toAlgebraic shouldBe "h8"
    Pos(4, 3).toAlgebraic shouldBe "e4"
    Pos(3, 4).toAlgebraic shouldBe "d5"
  }

  test("Pos addition should work correctly") {
    val pos = Pos(3, 3)
    pos + (1, 2) shouldBe Pos(4, 5)
    pos + (-1, -2) shouldBe Pos(2, 1)
    pos + (0, 0) shouldBe Pos(3, 3)
  }

  test("Move creation should work correctly") {
    val move = Move(Pos(4, 1), Pos(4, 3))
    move.from shouldBe Pos(4, 1)
    move.to shouldBe Pos(4, 3)
    move.promotion shouldBe None
    
    val moveWithPromotion = Move(Pos(4, 6), Pos(4, 7), Some(PieceType.Queen))
    moveWithPromotion.promotion shouldBe Some(PieceType.Queen)
  }

  test("Piece.fromFenChar should parse valid FEN pieces") {
    Piece.fromFenChar('K') shouldBe Some(Piece(Color.White, PieceType.King))
    Piece.fromFenChar('q') shouldBe Some(Piece(Color.Black, PieceType.Queen))
    Piece.fromFenChar('x') shouldBe None
  }

  test("CastlingRights should round-trip FEN notation") {
    CastlingRights.fromFen("Kq") shouldBe Some(
      CastlingRights(
        whiteKingSide = true,
        whiteQueenSide = false,
        blackKingSide = false,
        blackQueenSide = true
      )
    )
    CastlingRights(
      whiteKingSide = true,
      whiteQueenSide = false,
      blackKingSide = false,
      blackQueenSide = true
    ).toFen shouldBe "Kq"
    CastlingRights(false, false, false, false).toFen shouldBe "-"
  }

  test("GameState should parse and serialize FEN") {
    val fen = "r3k2r/pppq1ppp/2npbn2/4p3/2BPP3/2N2N2/PPP2PPP/R1BQ1RK1 b kq d3 4 8"
    val state = GameState.fromFen(fen).fold(msg => fail(msg), identity)
    state.toFen shouldBe fen
  }
