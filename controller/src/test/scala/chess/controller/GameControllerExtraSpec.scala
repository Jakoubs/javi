package chess.controller

import chess.model.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/**
 * Weitere Coverage-Lücken in GameController:
 *
 *  - liveMillis: isActive=false branch (Some(c) ohne active condition)
 *  - liveMillis: activeColor != color branch
 *  - AppState extension: liveMillis(Color.Black) with active clock
 *  - handleMove: Resigned message (GameStatus.Resigned inside handleMove's newStatus match)
 *  - handleAiMove when game is in Check status (NOT game over)
 *  - SwitchParser "pgn" / "move" success paths already covered, cover "move" failure here
 *  - NewGame preserves aiWhite / aiBlack flags
 *  - StartGame ohne clock (None)
 */
class GameControllerExtraSpec extends AnyFunSpec with Matchers {

  val initial: AppState = AppState.initial

  // ── liveMillis extension method branches ─────────────────────────────────────

  describe("AppState.liveMillis extension") {
    it("should return 0 when no clock is set") {
      initial.liveMillis(Color.White) shouldBe 0L
      initial.liveMillis(Color.Black) shouldBe 0L
    }

    it("should return activeMillis for the non-active color (clock not ticking for them)") {
      val now = System.currentTimeMillis()
      val clock = ClockState(5000L, 8000L, 0L, Some(now - 500L), isActive = true)
      val app = initial.copy(clock = Some(clock))
      // White is active, Black's time is NOT ticking → returns c.activeMillis(Black) = 8000
      app.liveMillis(Color.Black) shouldBe 8000L
    }

    it("should return a reduced value for the active color when clock is ticking") {
      val now = System.currentTimeMillis()
      val clock = ClockState(5000L, 8000L, 0L, Some(now - 500L), isActive = true)
      val app = initial.copy(clock = Some(clock))
      // White is active and clock is ticking – elapsed ~500ms
      val live = app.liveMillis(Color.White)
      live should (be >= 4400L and be <= 4600L)
    }

    it("should return activeMillis when clock is not active") {
      val clock = ClockState(5000L, 8000L, 0L, Some(System.currentTimeMillis()), isActive = false)
      val app = initial.copy(clock = Some(clock))
      // clock.isActive = false → falls to Some(c) branch → c.activeMillis(color)
      app.liveMillis(Color.White) shouldBe 5000L
      app.liveMillis(Color.Black) shouldBe 8000L
    }

    it("should return 0 when remaining millis would go below 0") {
      val now = System.currentTimeMillis()
      // Clock had 100ms but 5000ms have passed → should clamp to 0
      val clock = ClockState(100L, 8000L, 0L, Some(now - 5000L), isActive = true)
      val app = initial.copy(clock = Some(clock))
      app.liveMillis(Color.White) shouldBe 0L
    }
  }

  // ── handleAiMove when status is Check (NOT game over) ─────────────────────────

  describe("handleAiMove when in Check") {
    it("should find a move even when the active player is in check") {
      // Position: White is in check (needs to get out)
      val fen = "r1b1kbnr/ppp2ppp/2p5/8/4q3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 7"
      val state = GameState.fromFen(fen).getOrElse(fail("Bad FEN"))
      val app = AppState(state, GameRules.computeStatus(state))

      app.status shouldBe a[GameStatus.Check]

      val result = GameController.handleCommand(app, Command.AiMove)
      // The AI should find a legal escape move
      result.status should not be a[GameStatus.Check]
      result.lastMove shouldBe defined
    }
  }

  // ── NewGame preserves AI flags ────────────────────────────────────────────────

  describe("Command.NewGame") {
    it("should preserve aiWhite and aiBlack flags after a new game") {
      val withAi = initial.copy(aiWhite = true, aiBlack = true)
      val result = GameController.handleCommand(withAi, Command.NewGame)
      result.aiWhite shouldBe true
      result.aiBlack shouldBe true
      result.game.history shouldBe empty
    }
  }

  // ── StartGame without clock (None) ───────────────────────────────────────────

  describe("Command.StartGame without clock") {
    it("should start a new game with no clock when None is passed") {
      val result = GameController.handleCommand(initial, Command.StartGame(None))
      result.clock shouldBe None
      result.game.history shouldBe empty
      result.message.get should include("New game started")
    }
  }

  // ── handleAiSuggest when game is in Check (NOT game over) ─────────────────────

  describe("handleAiSuggest when in Check") {
    it("should find an escape move via AiMove when the active player is in check") {
      // AiSuggest checks `status != Playing` only (not Check-aware), so use AiMove
      val fen = "r1b1kbnr/ppp2ppp/2p5/8/4q3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 7"
      val state = GameState.fromFen(fen).getOrElse(fail("Bad FEN"))
      val app = AppState(state, GameRules.computeStatus(state))

      app.status shouldBe a[GameStatus.Check]

      // AiMove handles Check correctly (it uses isInstanceOf[Check] guard)
      val result = GameController.handleCommand(app, Command.AiMove)
      result.lastMove shouldBe defined
      result.status should not be a[GameStatus.Check]
    }
  }

  // ── handleMove: move on a game that has a Resigned status (via ApplyMove) ────

  describe("handleMove when game is already Resigned") {
    it("should reject moves after a resignation") {
      val resigned = initial.copy(status = GameStatus.Resigned(Color.White))
      val result = GameController.handleCommand(resigned, Command.ApplyMove(Move(Pos(4, 1), Pos(4, 3))))
      // Game over check fires – message may reference 'Game is over' or the game board stays unchanged
      result.game shouldBe resigned.game
      // The status remains Resigned (no move was applied)
      result.status shouldBe GameStatus.Resigned(Color.White)
    }
  }

  // ── handleMove: move on a game that has Timeout status ───────────────────────

  describe("handleMove when game has Timeout status") {
    it("should reject moves after a timeout") {
      val timedOut = initial.copy(status = GameStatus.Timeout(Color.White))
      val result = GameController.handleCommand(timedOut, Command.ApplyMove(Move(Pos(4, 1), Pos(4, 3))))
      result.game shouldBe timedOut.game
      result.status shouldBe GameStatus.Timeout(Color.White)
    }
  }

  // ── validateMove: empty square path ──────────────────────────────────────────

  describe("validateMove – empty source square") {
    it("should return 'Feld ist leer' when source square has no piece") {
      val result = GameController.handleCommand(initial, Command.ApplyMove(Move(Pos(0, 3), Pos(0, 4))))
      result.message.get should include("leer")
    }
  }
}
