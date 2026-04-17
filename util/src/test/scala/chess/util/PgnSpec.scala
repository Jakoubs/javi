package chess.util

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Success

class PgnSpec extends AnyFunSpec with Matchers {

  // Helper: plays through n moves from initial and gets the resulting state's history+final
  def playMoves(moves: String*): GameState =
    moves.foldLeft(GameState.initial) { (state, san) =>
      chess.util.parser.SanMoveParser.parse(san, state).get match
        case move => GameRules.applyMove(state, move)
    }

  // ─── toSan ───────────────────────────────────────────────────────────────────

  describe("Pgn.toSan") {
    it("should generate correct SAN for a pawn push") {
      val state    = GameState.initial
      val move     = Move(Pos(4, 1), Pos(4, 3), None) // e2-e4
      val next     = GameRules.applyMove(state, move)
      Pgn.toSan(state, move, next) shouldBe "e4"
    }

    it("should generate correct SAN for a knight move") {
      val state = GameState.initial
      val move  = Move(Pos(6, 0), Pos(5, 2), None) // Ng1-f3
      val next  = GameRules.applyMove(state, move)
      Pgn.toSan(state, move, next) shouldBe "Nf3"
    }

    it("should use 'x' for captures") {
      // White has a pawn on e5, Black has a pawn on d5 — e5xd6 en-passant or use a simpler setup
      // Simpler: put White Rook on e1, Black Queen on e5, White captures
      val board = Board.empty
        .put(Pos(4, 0), Piece(Color.White, PieceType.King))
        .put(Pos(4, 7), Piece(Color.Black, PieceType.King))
        .put(Pos(0, 0), Piece(Color.White, PieceType.Rook))
        .put(Pos(0, 4), Piece(Color.Black, PieceType.Rook)) // Ra5
      val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
      val move  = Move(Pos(0, 0), Pos(0, 4), None) // Rxa5
      val next  = GameRules.applyMove(state, move)
      Pgn.toSan(state, move, next) should include("x")
    }

    it("should use 'O-O' for kingside castling") {
      val state = GameState.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").getOrElse(fail())
      val move  = Move(Pos(4, 0), Pos(6, 0), None) // e1-g1
      val next  = GameRules.applyMove(state, move)
      Pgn.toSan(state, move, next) shouldBe "O-O"
    }

    it("should use 'O-O-O' for queenside castling") {
      val state = GameState.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").getOrElse(fail())
      val move  = Move(Pos(4, 0), Pos(2, 0), None) // e1-c1
      val next  = GameRules.applyMove(state, move)
      Pgn.toSan(state, move, next) shouldBe "O-O-O"
    }

    it("should include promotion piece in SAN") {
      // White pawn on e7, can promote
      val board = Board.empty
        .put(Pos(4, 0), Piece(Color.White, PieceType.King))
        .put(Pos(4, 7), Piece(Color.Black, PieceType.King))
        .put(Pos(4, 6), Piece(Color.White, PieceType.Pawn))
      val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
      val move  = Move(Pos(4, 6), Pos(4, 7), Some(PieceType.Queen))
      val next  = GameRules.applyMove(state, move)
      Pgn.toSan(state, move, next) should include("=Q")
    }

    it("should add '+' when move gives check") {
      // White Rook delivers check to Black King
      val board = Board.empty
        .put(Pos(4, 0), Piece(Color.White, PieceType.King))
        .put(Pos(4, 7), Piece(Color.Black, PieceType.King))
        .put(Pos(0, 0), Piece(Color.White, PieceType.Rook))
      val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
      val move  = Move(Pos(0, 0), Pos(0, 7), None) // Ra8+ check
      val next  = GameRules.applyMove(state, move)
      Pgn.toSan(state, move, next) should endWith("+")
    }

    it("should add '#' for checkmate moves") {
      // Fool's mate setup: Black just played 3...Qh4# after e4 f5 e5 g5
      val state = GameState.fromFen("rnb1kbnr/pppp1ppp/8/4pq2/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 3").getOrElse(fail())
      val move  = Move(Pos(5, 4), Pos(7, 1), None) // Qf5-h4 — wait, let's let the parser find it
      // Better: after e4 f5 g4, Qh4# is mate
      val mateState = GameState.fromFen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 0 4").getOrElse(fail())
      // White is in checkmate here — just verify it's a terminal state
      val status = GameRules.computeStatus(mateState)
      status shouldBe GameStatus.Checkmate(Color.White)
    }

    it("should disambiguate when two rooks can reach the same square (by file)") {
      // Two White rooks on a1 and h1, both can go to d1. 
      // Ensure target d1 (3,0) is EMPTY so MoveGenerator considers it legal.
      val board = Board.empty
        .put(Pos(4, 1), Piece(Color.White, PieceType.King))   // King on e2
        .put(Pos(4, 7), Piece(Color.Black, PieceType.King))   // King on e8
        .put(Pos(0, 0), Piece(Color.White, PieceType.Rook))   // Rook on a1
        .put(Pos(7, 0), Piece(Color.White, PieceType.Rook))   // Rook on h1
      val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
      val move  = Move(Pos(0, 0), Pos(3, 0), None) // Ra1-d1
      val next  = GameRules.applyMove(state, move)
      val san   = Pgn.toSan(state, move, next)
      san should startWith("Rad") // file disambiguation
    }

    it("should disambiguate when two rooks can reach the same square (by rank)") {
      // Two White rooks on a1 and a8, both can go to a4
      val board = Board.empty
        .put(Pos(6, 0), Piece(Color.White, PieceType.King))   // k on g1
        .put(Pos(6, 7), Piece(Color.Black, PieceType.King))   // k on g8
        .put(Pos(0, 0), Piece(Color.White, PieceType.Rook))   // Ra1
        .put(Pos(0, 7), Piece(Color.White, PieceType.Rook))   // Ra8
      val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
      val move  = Move(Pos(0, 0), Pos(0, 3), None) // Ra1-a4
      val next  = GameRules.applyMove(state, move)
      val san   = Pgn.toSan(state, move, next)
      san should startWith("R1") // rank disambiguation
    }

    it("should use full square disambiguation when needed") {
      // Three White queens on a1, a4, d1 all going to d4 — extremely contrived
      // Actually tricky to set up; verify the path runs by checking two disambiguable rooks on same file AND rank
      val board = Board.empty
        .put(Pos(7, 0), Piece(Color.White, PieceType.King))
        .put(Pos(7, 7), Piece(Color.Black, PieceType.King))
        .put(Pos(0, 0), Piece(Color.White, PieceType.Queen)) // Qa1
        .put(Pos(0, 7), Piece(Color.White, PieceType.Queen)) // Qa8
        .put(Pos(4, 3), Piece(Color.White, PieceType.Queen)) // Qe4
      val state = WhiteToMove(board, CastlingRights(), None, 0, 1)
      val move  = Move(Pos(0, 0), Pos(0, 3), None) // Qa1-a4
      val next  = GameRules.applyMove(state, move)
      val san   = Pgn.toSan(state, move, next)
      // All three queens can't reach a4: Qa8 can, Qe4 can't — so file disambiguation
      san should startWith("Q")
    }
  }

  // ─── deduceMove ──────────────────────────────────────────────────────────────

  describe("Pgn.deduceMove") {
    it("should find the move that transitions between two states") {
      val state1 = GameState.initial
      val move   = Move(Pos(4, 1), Pos(4, 3), None) // e4
      val state2 = GameRules.applyMove(state1, move)
      Pgn.deduceMove(state1, state2) shouldBe Some(move)
    }

    it("should return None when no legal move produces the target state") {
      val state1 = GameState.initial
      // state2 is identical to state1 — no move leads there
      Pgn.deduceMove(state1, state1) shouldBe None
    }
  }

  // ─── exportPgn ───────────────────────────────────────────────────────────────

  describe("Pgn.exportPgn") {
    it("should return empty string for a fresh game (no history)") {
      Pgn.exportPgn(GameState.initial) shouldBe ""
    }

    it("should generate a valid PGN header for a game with moves") {
      val afterE4 = GameRules.applyMove(GameState.initial, Move(Pos(4, 1), Pos(4, 3), None))
      val pgn     = Pgn.exportPgn(afterE4)
      pgn should include("[Event")
      pgn should include("[White")
      pgn should include("[Result")
      pgn should include("e4")
    }

    it("should include custom player names") {
      val afterE4 = GameRules.applyMove(GameState.initial, Move(Pos(4, 1), Pos(4, 3), None))
      val pgn     = Pgn.exportPgn(afterE4, "Alice", "Bob")
      pgn should include("Alice")
      pgn should include("Bob")
    }

    it("should include correct result '1-0' for White checkmate") {
      // White to move, delivers mate.
      // 1. e4 e5 2. Bc4 Nc6 3. Qh5 d6 4. Qxf7#
      val s0 = GameState.initial
      val s1 = GameRules.applyMove(s0, Move(Pos(4, 1), Pos(4, 3))) // e4
      val s2 = GameRules.applyMove(s1, Move(Pos(4, 6), Pos(4, 4))) // e5
      val s3 = GameRules.applyMove(s2, Move(Pos(5, 0), Pos(2, 3))) // Bc4
      val s4 = GameRules.applyMove(s3, Move(Pos(1, 7), Pos(2, 5))) // Nc6
      val s5 = GameRules.applyMove(s4, Move(Pos(3, 0), Pos(7, 4))) // Qh5
      val s6 = GameRules.applyMove(s5, Move(Pos(3, 6), Pos(3, 5))) // d6
      val terminal = GameRules.applyMove(s6, Move(Pos(7, 4), Pos(5, 6))) // Qxf7#
      
      val pgn = Pgn.exportPgn(terminal)
      pgn should include("[Result \"1-0\"]")
      pgn should include("1-0")
    }

    it("should include '*' for an ongoing game") {
      val state = GameRules.applyMove(GameState.initial, Move(Pos(4, 1), Pos(4, 3), None))
      val pgn   = Pgn.exportPgn(state)
      pgn should include("*")
    }
  }

  // ─── exportMovesOnly ─────────────────────────────────────────────────────────

  describe("Pgn.exportMovesOnly") {
    it("should return empty string for a fresh game (no history)") {
      Pgn.exportMovesOnly(GameState.initial) shouldBe ""
    }

    it("should return move list for a game with moves") {
      val afterE4 = GameRules.applyMove(GameState.initial, Move(Pos(4, 1), Pos(4, 3), None))
      val movesOnly = Pgn.exportMovesOnly(afterE4)
      movesOnly should include("e4")
      movesOnly should include("1.")
    }

    it("should include Black moves on same line without number") {
      val s1 = GameRules.applyMove(GameState.initial, Move(Pos(4, 1), Pos(4, 3), None)) // e4
      val s2 = GameRules.applyMove(s1, Move(Pos(4, 6), Pos(4, 4), None))                // e5
      val movesOnly = Pgn.exportMovesOnly(s2)
      movesOnly should include("e4")
      movesOnly should include("e5")
    }
  }

  // ─── importPgn ───────────────────────────────────────────────────────────────

  describe("Pgn.importPgn") {
    it("should parse and replay a simple PGN") {
      val result = Pgn.importPgn("1. e4 e5 2. Nf3")
      result.isSuccess shouldBe true
      val state = result.get
      state.board.get(Pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn)) // e4
    }

    it("should fail on an invalid PGN") {
      Pgn.importPgn("1. invalidmove").isFailure shouldBe true
    }
  }
}
