# ♟ Terminal Chess in Scala

A fully playable, legal-moves-only chess game for the terminal, built in pure Scala 3 with MVC architecture inspired by lichess.

---

## Architecture

```
chess/
├── model/
│   ├── Types.scala          # Color, PieceType, Piece, Pos, Move, CastlingRights, GameStatus
│   ├── Board.scala          # Board + GameState (immutable value types)
│   ├── MoveGenerator.scala  # Pseudo-legal & legal move generation, attack detection
│   └── GameRules.scala      # State transitions, draw/checkmate/stalemate detection
├── view/
│   └── TerminalView.scala   # ANSI board rendering (lichess-style colours, unicode pieces)
├── controller/
│   └── GameController.scala # Command ADT, CommandParser, AppState, game loop
└── Main.scala
```

### Model
Pure, immutable data and logic. Zero I/O. Fully unit-testable.

- `Board` – Map-based 8×8 board
- `GameState` – Complete snapshot (board + active colour + castling rights + en passant + clocks + history)
- `MoveGenerator` – Generates legal moves for all piece types including castling, en passant, promotion
- `GameRules` – Applies moves, updates state, detects check/checkmate/stalemate/draws

### View
Stateless renderer receiving a `GameState` and rendering state to a `String`.  
ANSI colours mimic lichess's board palette. Unicode chess symbols ♔♕♖♗♘♙.

### Controller
`CommandParser` parses raw strings into a `Command` ADT.  
`GameController` maintains `AppState` and dispatches commands functionally (pure `handleCommand` function).

---

## Features

- ✅ All legal moves enforced (pins, checks, discovered checks)
- ✅ Castling (king-side & queen-side, with transit-square attack detection)
- ✅ En passant
- ✅ Pawn promotion (q/r/b/n)
- ✅ Checkmate & stalemate detection
- ✅ 50-move rule
- ✅ Threefold repetition
- ✅ Insufficient material draw
- ✅ Undo
- ✅ Resign & draw by agreement
- ✅ Highlight legal moves with `moves e2`
- ✅ Last-move highlighting
- ✅ Board flip
- ✅ Comprehensive test suite

---

## Requirements

- **Java 11+**
- **sbt 1.9+** (`brew install sbt` / `sdk install sbt`)

---

## Run (All UIs - TUI + GUI + WebUI)

Run all three interfaces simultaneously, sharing the same game state:

```bash
sbt run
```

This will start:
- **TUI**: Terminal interface in the current terminal
- **GUI**: Desktop window with Scala Swing interface  
- **WebUI**: Web interface at http://localhost:8080

All three UIs share the same game session - moves made in one UI will instantly reflect in the others.

## Run (TUI only)

```bash
sbt "runMain chess.Main"
```

## Run (GUI only)

Desktop GUI using Scala Swing:

```bash
sbt runMain chess.GuiMain
```

## Run (WebUI only)

Web interface only:

```bash
sbt "runMain chess.web.WebMain"
```

Then start the frontend:

```bash
cd webui
npm run dev
```

Access at http://localhost:5173

## Test

```bash
sbt test
```

## Build fat JAR

```bash
sbt assembly
java -jar target/scala-3.3.1/chess-assembly-1.0.0.jar
```

---

## Commands

| Command      | Description                              |
|-------------|------------------------------------------|
| `e2e4`      | Move piece from e2 to e4                 |
| `e7e8q`     | Move + promote to queen (q/r/b/n)        |
| `moves e2`  | Highlight legal moves from e2            |
| `flip`      | Flip board perspective                   |
| `undo`      | Undo last move                           |
| `resign`    | Resign the game                          |
| `draw`      | Offer / accept draw                      |
| `new`       | Start a new game                         |
| `help`      | Show help                                |
| `quit`      | Quit                                     |

---

## Design Principles

- **Functional**: `GameState`, `Board`, `Move` are all immutable case classes. State transitions return new values.
- **Testable**: Model and Controller logic are pure functions with no side effects. Tests cover all piece types, special moves, and edge cases.
- **MVC separation**: View only renders; Controller only dispatches; Model only contains rules.
