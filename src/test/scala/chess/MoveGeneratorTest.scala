package chess

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import chess.model.*

class MoveGeneratorTest extends AnyFunSuite with Matchers:

  private def stateFromFen(fen: String): GameState =
    GameState.fromFen(fen).fold(msg => fail(msg), identity)

  private def pos(square: String): Pos =
    Pos.fromAlgebraic(square).getOrElse(fail(s"Invalid square: $square"))

  test("white pawn on e2 can advance one or two squares") {
    val state = GameState.initial
    val moves = MoveGenerator.legalMovesFrom(state, pos("e2")).map(_.to)
    moves should contain(pos("e3"))
    moves should contain(pos("e4"))
  }

  test("white pawn cannot advance if blocked") {
    val state = stateFromFen("4k3/8/8/8/8/4p3/4P3/4K3 w - - 0 1")
    val moves = MoveGenerator.legalMovesFrom(state, pos("e2"))
    moves shouldBe empty
  }

  test("white pawn can capture diagonally") {
    val state = stateFromFen("4k3/8/8/3p1p2/4P3/8/8/4K3 w - - 0 1")
    val targets = MoveGenerator.legalMovesFrom(state, pos("e4")).map(_.to)
    targets should contain(pos("d5"))
    targets should contain(pos("f5"))
  }

  test("pawn promotion generates 4 moves on the promotion square") {
    val state = stateFromFen("7k/4P3/8/8/8/8/8/4K3 w - - 0 1")
    val moves = MoveGenerator.legalMovesFrom(state, pos("e7"))
    moves.flatMap(_.promotion).distinct should contain allOf
      (PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight)
    moves.count(_.to == pos("e8")) shouldBe 4
  }

  test("knight on e4 has 8 possible target squares in open position") {
    val state = stateFromFen("4k3/8/8/8/4N3/8/8/4K3 w - - 0 1")
    MoveGenerator.legalMovesFrom(state, pos("e4")) should have length 8
  }

  test("knight on a1 has only 2 target squares") {
    val state = stateFromFen("7k/8/8/8/8/8/8/N6K w - - 0 1")
    MoveGenerator.legalMovesFrom(state, pos("a1")) should have length 2
  }

  test("rook on e4 in open position has 14 moves") {
    val state = stateFromFen("7k/8/8/8/4R3/8/8/K7 w - - 0 1")
    MoveGenerator.legalMovesFrom(state, pos("e4")) should have length 14
  }

  test("bishop on e4 in open position has 13 moves") {
    val state = stateFromFen("7k/8/8/8/4B3/8/8/K7 w - - 0 1")
    MoveGenerator.legalMovesFrom(state, pos("e4")) should have length 13
  }

  test("king on e4 in open position has 8 moves") {
    val state = stateFromFen("4k3/8/8/8/4K3/8/8/8 w - - 0 1")
    MoveGenerator.legalMovesFrom(state, pos("e4")) should have length 8
  }

  test("king cannot move into check") {
    val state = stateFromFen("3rk3/8/8/8/8/8/8/4K3 w - - 0 1")
    val targets = MoveGenerator.legalMovesFrom(state, pos("e1")).map(_.to)
    targets should not contain pos("d1")
    targets should not contain pos("d2")
  }

  test("king in check must resolve the check") {
    val state = stateFromFen("4r2k/8/8/8/8/8/8/4K3 w - - 0 1")
    val legal = MoveGenerator.legalMoves(state)
    legal should not be empty
    legal.forall { move =>
      val next = GameRules.applyMove(state, move)
      !MoveGenerator.isInCheck(next, Color.White)
    } shouldBe true
  }

  test("isInCheck is true when queen attacks king") {
    val state = stateFromFen("4q2k/8/8/8/8/8/8/4K3 w - - 0 1")
    MoveGenerator.isInCheck(state, Color.White) shouldBe true
  }

  test("en passant is legal when target square is set") {
    val state = stateFromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
    val moves = MoveGenerator.legalMovesFrom(state, pos("e5")).map(_.to)
    moves should contain(pos("d6"))
  }

  test("en passant capture removes the captured pawn") {
    val state = stateFromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
    val move = Move(pos("e5"), pos("d6"))
    val newState = GameRules.applyMove(state, move)
    newState.board.get(pos("d5")) shouldBe None
    newState.board.get(pos("d6")) shouldBe Some(Piece(Color.White, PieceType.Pawn))
  }

  test("castling is available when path is clear") {
    val state = stateFromFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
    val moves = MoveGenerator.legalMovesFrom(state, pos("e1")).map(_.to)
    moves should contain(pos("g1"))
  }

  test("castling is blocked when a square is under attack") {
    val state = stateFromFen("4kr2/8/8/8/8/8/8/4K2R w K - 0 1")
    val moves = MoveGenerator.legalMovesFrom(state, pos("e1")).map(_.to)
    moves should not contain pos("g1")
  }

  test("FEN active color controls which side can move") {
    val state = stateFromFen("4k3/8/8/8/8/8/4p3/4K3 b - - 0 1")
    val moves = MoveGenerator.legalMoves(state)
    moves should not be empty
    moves.forall(move => state.board.get(move.from).exists(_.color == Color.Black)) shouldBe true
  }

  test("missing castling rights in FEN disable castling") {
    val state = stateFromFen("4k3/8/8/8/8/8/8/4K2R w - - 0 1")
    val moves = MoveGenerator.legalMovesFrom(state, pos("e1")).map(_.to)
    moves should not contain pos("g1")
  }
