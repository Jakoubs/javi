# ♟ Terminal Chess in Scala
[![Coverage Status](https://coveralls.io/repos/github/Jakoubs/javi/badge.svg?branch=tui)](https://coveralls.io/github/Jakoubs/javi?branch=tui)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Scala Version](https://img.shields.io/badge/scala-3.3.1-red)](https://scala-lang.org/)
[![sbt Build](https://img.shields.io/badge/sbt-1.9+-blue)](https://scala-sbt.org/)
[![CI](https://img.shields.io/github/actions/workflow/status/Jakoubs/javi/ci.yml)](https://github.com/Jakoubs/javi/actions/workflows/ci.yml)

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

## Run

```bash
cd chess
sbt run
```

## Test

```bash
sbt test
```

### Generate Coverage Locally

```bash
# Run tests with coverage
sbt clean coverage test

# Generate HTML report
sbt coverageReport

# View the report
open target/scala-3.3.1/scoverage-report/index.html
```

### Coverage Goals

- **Statement Coverage**: 80% minimum target
- **Branch Coverage**: 70% minimum target
- **Excluded**: UI components (`.view.*`), web components (`.web.*`), main classes

### Automated Coverage

Coverage is automatically generated and reported on:

- **Push** to `main` and `functional-improvements` branches
- **Pull Requests** to `main` and `functional-improvements` branches

### Coverage Reports

- **Local**: `target/scala-3.3.1/scoverage-report/index.html`
- **Coveralls**: https://coveralls.io/r/YOUR_USERNAME/chess?branch=main
- **Codecov**: https://codecov.io/gh/YOUR_USERNAME/chess
- **GitHub Actions**: Available as workflow artifacts

### Coverage Configuration

```scala
// build.sbt
coverageExcludedPackages := ".*\\.view\\..*;.*\\.web\\..*;.*\\.Main.*"
coverageMinimumStmtTotal := 80
coverageMinimumBranchTotal := 70
coverageFailOnMinimum := false
coverageHighlighting := true
```

### View Coverage Details

1. **Local Report**: Open `target/scala-3.3.1/scoverage-report/index.html`
2. **Coveralls Dashboard**: Visit https://coveralls.io/r/YOUR_USERNAME/chess
3. **Codecov Dashboard**: Visit https://codecov.io/gh/YOUR_USERNAME/chess
4. **GitHub Actions**: Check workflow runs for coverage summaries
5. **Pull Requests**: Coverage diff in PR checks

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

## REST API Guide

Ein detaillierter Implementierungs-Guide fuer die REST API liegt in `REST_API_GUIDE.md`.

---

## PlantUML Diagramme automatisch generieren

Die automatische Diagramm-Erstellung laeuft in zwei Workflows:

1. `auto-docs.yml`: generiert `.puml` aus Scala-Sourcecode
2. `plantUML.yml`: rendert `.puml` nach `.svg`

Generierte PUML-Datei:

- `diagrams/generated/scala-architecture.puml`

- Trigger 1: Push auf `main` bei Aenderungen in `src/main/scala/**/*.scala`
- Trigger 2: Push auf `main` bei Aenderungen in `diagrams/**/*.puml`
- Output: SVG-Dateien in `out/`
- Commit: Aktualisierte PUML/SVG-Dateien werden vom jeweiligen Workflow automatisch committed

### Lokal testen

Voraussetzung lokal: installierte `python3`- und `plantuml`-CLI.

```bash
python3 ./scripts/generate-scala-puml.py
```

```bash
sh ./scripts/generate-diagrams.sh
```

### In GitHub Actions ausloesen

1. Aendere Scala-Code in `src/main/scala/` und pushe nach `main`.
2. `auto-docs.yml` erstellt/aktualisiert `diagrams/generated/scala-architecture.puml`.
3. Dieser Push triggert `plantUML.yml`, das SVGs in `out/` erzeugt.

Beide Workflows kannst du alternativ manuell ueber `workflow_dispatch` starten.

---

## Design Principles

- **Functional**: `GameState`, `Board`, `Move` are all immutable case classes. State transitions return new values.
- **Testable**: Model and Controller logic are pure functions with no side effects. Tests cover all piece types, special moves, and edge cases.
- **MVC separation**: View only renders; Controller only dispatches; Model only contains rules.
