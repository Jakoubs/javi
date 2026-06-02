# Javi Chess Performance Optimization

This walkthrough summarizes the integration of performance benchmarking loops and the optimization of core engine logic in the Javi Chess project.

## 1. Benchmarking Infrastructure Established

We introduced three standard performance assessment tools without bloating the main build profile:
- **k6 Load Tests**: Set up a JavaScript scenario (`perf/k6_load_test.js`) defining ramp-up user load hitting core endpoints (`/ping`, `/api/state`, `/api/command`, `/api/legal-moves`). Thresholds enforce `p(95) < 500ms` and a `<1%` error rate.
- **Gatling Simulation**: Created a Java-based Gatling scenario (`perf/gatling/src/test/java/chess/perf/ChessApiSimulation.java`) simulating an actual user chess game flow (state retrieval followed by moves) with continuous load, asserting the same `p(95)` targets. It executes independently using a Maven wrapper.
- **JMH Microbenchmarks**: Added `sbt-jmh` to `project/plugins.sbt` and an isolated `benchmark` subproject to measure throughput and allocation overhead for hot methods (`toFenPlacement`, `fromFen`, `legalMoves`).

## 2. Identified Hotspots

Using the JMH baseline, we identified that **`Board.toFenPlacement`**—a method responsible for parsing the immutable board into FEN syntax—was taking ~1.8 microseconds per call. Because it is invoked repeatedly per board instance, especially during history traversal inside rules (`isThreefoldRepetition`), its high allocation rate (constantly creating `Pos` case classes to look up pieces in a map) constituted a measurable bottleneck.

```scala
// The bottleneck
pieces.get(Pos(col, row)) // created 64 Pos objects per call
```

## 3. Optimizations Applied

We mitigated this hotspot with two key adjustments in `chess/model/Board.scala`:

> [!TIP]
> **Memoization**: Converted `def toFenPlacement` to `lazy val toFenPlacement`, ensuring that subsequent calls for the same board state execute in `O(1)` time.

> [!TIP]
> **Zero Allocation Path**: Introduced an internal `posCache: Array[Pos]` to the `Board` companion object, pre-populating all 64 possible chess board coordinates during class initialization. The string builder loop now uses `pieces.get(Board.posCache(row * 8 + col))`, preventing object allocation altogether.

## 4. Verification & Results

We validated the optimizations by running the SBT test suite (`sbt model/test`), confirming zero functional regressions.

Re-running the benchmarks showcased a drastic improvement in the microbenchmark level while successfully verifying that the REST endpoints easily meet the service-level agreements (SLAs):

- **Microbenchmark (JMH)**: `toFenPlacement` execution time dropped from `~1.8 us/op` to `<0.001 us/op` for subsequent invocations due to memoization and pre-allocation optimizations.
- **Macro Load (k6/Gatling)**: Response times remained heavily bounded, with the P95 for both tests resting firmly around **~3-5ms**, orders of magnitude below the `500ms` target latency under loads of 20 concurrent simulated virtual users.

For complete quantitative metrics, refer to the [Performance Results Summary](file:///home/jakobsteiner/.gemini/antigravity/brain/30a45859-e6fc-4da0-a24f-70852c641b60/perf_results.md).
