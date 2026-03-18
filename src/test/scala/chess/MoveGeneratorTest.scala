package chess

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import chess.model.*

class MoveGeneratorTest extends AnyFunSuite with Matchers:

  // ── Helpers ────────────────────────────────────────────────────────────────

  def stateWith(pieces: (Pos, Piece)*): GameState =
    GameState.initial.copy(
      board = Board(pieces.toMap),
      enPassantTarget = None,
      castlingRights  = CastlingRights(false, false, false, false)
    )

  def white(pt: PieceType): Piece = Piece(Color.White, pt)
  def black(pt: PieceType): Piece = Piece(Color.Black, pt)

  def pos(s: String): Pos = Pos.fromAlgebraic(s).get

  // ── Pawn tests ─────────────────────────────────────────────────────────────

  test("white pawn on e2 can advance one or two squares") {
    val state = GameState.initial
    val moves = MoveGenerator.legalMovesFrom(state, pos("e2")).map(_.to)
    moves should contain(pos("e3"))
    moves should contain(pos("e4"))
  }

  test("white pawn cannot advance if blocked") {
    val state = stateWith(
      pos("e2") -> white(PieceType.Pawn),
      pos("e3") -> black(PieceType.Pawn),
      pos("e1") -> white(PieceType.King),
      pos("e8") -> black(PieceType.King)
    )
    val moves = MoveGenerator.legalMovesFrom(state, pos("e2"))
    moves shouldBe empty
  }

  test("white pawn can capture diagonally") {
    val state = stateWith(
      pos("e4") -> white(PieceType.Pawn),
      pos("d5") -> black(PieceType.Pawn),
      pos("f5") -> black(PieceType.Pawn),
      pos("e1") -> white(PieceType.King),
      pos("e8") -> black(PieceType.King)
    )
    val targets = MoveGenerator.legalMovesFrom(state, pos("e4")).map(_.to)
    targets should contain(pos("d5"))
    targets should contain(pos("f5"))
  }

  test("pawn promotion generates 4 moves per target square") {
    val state = stateWith(
      pos("e7") -> white(PieceType.Pawn),
      pos("e1") -> white(PieceType.King),
      pos("a8") -> black(PieceType.King)
    )
    val moves = MoveGenerator.legalMovesFrom(state, pos("e7"))
    moves.flatMap(_.promotion).distinct should contain allOf
      (PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight)
    moves.count(_.to == pos("e8")) shouldBe 4
  }

  // ── Knight tests ───────────────────────────────────────────────────────────

  test("knight on e4 has 8 possible target squares in open position") {
    val state = stateWith(
      pos("e4") -> white(PieceType.Knight),
      pos("e1") -> white(PieceType.King),
      pos("e8") -> black(PieceType.King)
    )
    MoveGenerator.legalMovesFrom(state, pos("e4")) should have length 8
  }

  test("knight on a1 has only 2 target squares") {
    val state = stateWith(
      pos("a1") -> white(PieceType.Knight),
      pos("h1") -> white(PieceType.King),
      pos("h8") -> black(PieceType.King)
    )
    MoveGenerator.legalMovesFrom(state, pos("a1")) should have length 2
  }

  // ── Sliding piece tests ────────────────────────────────────────────────────

  test("rook on e4 in open position has 14 moves") {
    val state = stateWith(
      pos("e4") -> white(PieceType.Rook),
      pos("a1") -> white(PieceType.King),
      pos("h8") -> black(PieceType.King)
    )
    MoveGenerator.legalMovesFrom(state, pos("e4")) should have length 14
  }

  test("bishop on e4 in open position has 13 moves") {
    val state = stateWith(
      pos("e4") -> white(PieceType.Bishop),
      pos("a1") -> white(PieceType.King),
      pos("h8") -> black(PieceType.King)
    )
    MoveGenerator.legalMovesFrom(state, pos("e4")) should have length 13
  }

  // ── King tests ─────────────────────────────────────────────────────────────

  test("king on e4 in open position has 8 moves") {
    val state = stateWith(
      pos("e4") -> white(PieceType.King),
      pos("e8") -> black(PieceType.King)
    )
    MoveGenerator.legalMovesFrom(state, pos("e4")) should have length 8
  }

  test("king cannot move into check") {
    val state = stateWith(
      pos("e1") -> white(PieceType.King),
      pos("d8") -> black(PieceType.Rook),
      pos("e8") -> black(PieceType.King)
    )
    val targets = MoveGenerator.legalMovesFrom(state, pos("e1")).map(_.to)
    targets should not contain pos("d1")
    targets should not contain pos("d2")
  }

  // ── Check detection ────────────────────────────────────────────────────────

  test("king in check must resolve the check") {
    val state = stateWith(
      pos("e1") -> white(PieceType.King),
      pos("e8") -> black(PieceType.Rook),
      pos("h8") -> black(PieceType.King)
    )
    val legal = MoveGenerator.legalMoves(state)
    // All moves must leave the king out of check
    legal should not be empty
    legal.forall { m =>
      val next = GameRules.applyMove(state, m)
      !MoveGenerator.isInCheck(next, Color.White)
    } shouldBe true
  }

  test("isInCheck is true when queen attacks king") {
    val state = stateWith(
      pos("e1") -> white(PieceType.King),
      pos("e8") -> black(PieceType.Queen),
      pos("h8") -> black(PieceType.King)
    )
    MoveGenerator.isInCheck(state, Color.White) shouldBe true
  }

  // ── En passant ─────────────────────────────────────────────────────────────

  test("en passant is legal when target square is set") {
    val state = stateWith(
      pos("e5") -> white(PieceType.Pawn),
      pos("d5") -> black(PieceType.Pawn),
      pos("e1") -> white(PieceType.King),
      pos("e8") -> black(PieceType.King)
    ).copy(enPassantTarget = Some(pos("d6")))

    val moves = MoveGenerator.legalMovesFrom(state, pos("e5")).map(_.to)
    moves should contain(pos("d6"))
  }

  test("en passant capture removes the captured pawn") {
    val state = stateWith(
      pos("e5") -> white(PieceType.Pawn),
      pos("d5") -> black(PieceType.Pawn),
      pos("e1") -> white(PieceType.King),
      pos("e8") -> black(PieceType.King)
    ).copy(enPassantTarget = Some(pos("d6")))

    val move     = Move(pos("e5"), pos("d6"))
    val newState = GameRules.applyMove(state, move)
    newState.board.get(pos("d5")) shouldBe None
    newState.board.get(pos("d6")) shouldBe Some(white(PieceType.Pawn))
  }

  // ── Castling ───────────────────────────────────────────────────────────────

  test("castling is available when path is clear") {
    val state = stateWith(
      pos("e1") -> white(PieceType.King),
      pos("h1") -> white(PieceType.Rook),
      pos("e8") -> black(PieceType.King)
    ).copy(castlingRights = CastlingRights(whiteKingSide = true, whiteQueenSide = false,
                                            blackKingSide = false, blackQueenSide = false))

    val moves = MoveGenerator.legalMovesFrom(state, pos("e1")).map(_.to)
    moves should contain(pos("g1"))   // king-side castle
  }

  test("castling is blocked when a square is under attack") {
    val state = stateWith(
      pos("e1") -> white(PieceType.King),
      pos("h1") -> white(PieceType.Rook),
      pos("f8") -> black(PieceType.Rook),    // attacks f1 transit square
      pos("e8") -> black(PieceType.King)
    ).copy(castlingRights = CastlingRights(whiteKingSide = true, whiteQueenSide = false,
                                            blackKingSide = false, blackQueenSide = false))

    val moves = MoveGenerator.legalMovesFrom(state, pos("e1")).map(_.to)
    moves should not contain pos("g1")
  }

  // ── Checkmate / Stalemate ──────────────────────────────────────────────────

  test("Scholar's mate is checkmate") {
    val s0 = GameState.initial
    val moves = List(
      Move(pos("e2"), pos("e4")),
      Move(pos("e7"), pos("e5")),
      Move(pos("f1"), pos("c4")),
      Move(pos("b8"), pos("c6")),
      Move(pos("d1"), pos("h5")),
      Move(pos("a7"), pos("a6")),
      Move(pos("h5"), pos("f7"))
    )
    val finalState = moves.foldLeft(s0)(GameRules.applyMove)
    GameRules.computeStatus(finalState) shouldBe GameStatus.Checkmate(Color.Black)
  }

  test("stalemate is detected correctly") {
    // Classic stalemate: black king can't move
    val state = stateWith(
      pos("a8") -> black(PieceType.King),
      pos("b6") -> white(PieceType.Queen),
      pos("c7") -> white(PieceType.King)
    ).copy(activeColor = Color.Black)

    GameRules.computeStatus(state) shouldBe GameStatus.Stalemate
  }
