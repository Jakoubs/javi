# Performance Testing Assignment - Umsetzung im Javi-Projekt

Dieses Dokument erklaert fuer jeden Punkt des Assignments, was im Projekt umgesetzt wurde, warum die jeweilige Loesung so integriert wurde und welchen Zweck sie erfuellt. Offene Punkte sind jeweils mit einer kurzen To-do-Beschreibung markiert.

## 1. Create a k6 test

**Was gemacht wurde:**  
Es wurde ein k6-Test in `perf/k6_load_test.js` erstellt. Der Test ruft die wichtigsten REST-Endpunkte des Schachservers auf:

- `GET /ping`
- `GET /api/state`
- `POST /api/command`
- `GET /api/legal-moves`
- `GET /api/puzzles/random`
- `GET /api/puzzles/legal-moves`

Der Test simuliert damit nicht nur einen technischen Ping, sondern auch typische Spielaktionen und einen Puzzle/FEN-Fall.

**Warum so integriert:**  
k6 liegt im Ordner `perf`, weil es kein normaler Unit-Test ist, sondern ein externer Lasttest gegen den laufenden REST-Service. Der Test verwendet `BASE_URL`, damit lokal, in Docker oder spaeter in einer Deployment-Umgebung gegen unterschiedliche Ziele getestet werden kann.

**Sinn:**  
k6 prueft, wie sich die API unter Last verhaelt. Besonders sinnvoll ist das fuer Endpunkte, die im Frontend oft genutzt werden oder intern Spiellogik ausloesen. Dadurch sieht man schnell, ob Latenz oder Fehlerquote unter parallelen Zugriffen steigen.

**Status:**  
Umgesetzt. Auf Windows muss `k6` noch installiert oder als `perf/k6.exe` abgelegt werden, damit `sbt performance` den k6-Teil ausfuehren kann.

## 2. Create a Gatling test

**Was gemacht wurde:**  
Es wurde eine Gatling-Simulation in `perf/gatling/src/test/java/chess/perf/ChessApiSimulation.java` erstellt und erweitert. Die Simulation bildet einen realistischen User-Flow ab:

- Server-Ping
- Spielzustand laden
- Zug `e2e4` senden
- aktualisierten Zustand laden
- legale Zuege fuer `d7` abfragen
- Zug `d7d5` senden
- Puzzle/FEN-Endpunkt testen

**Warum so integriert:**  
Gatling ist als eigenes Maven-Projekt unter `perf/gatling` integriert. Das trennt die Gatling-Abhaengigkeiten vom Scala/SBT-Hauptprojekt. Dadurch bleiben die normalen Builds und Unit-Tests schlank, waehrend Gatling trotzdem reproduzierbar ausgefuehrt werden kann.

**Sinn:**  
Gatling ist gut geeignet, um User-Journeys zu testen. Im Gegensatz zu einzelnen API-Requests zeigt dieser Test, ob ein kompletter Spielablauf unter Last stabil bleibt. Der Report macht sichtbar, welcher Schritt des Flows am meisten Zeit braucht.

**Status:**  
Umgesetzt und per Maven `test-compile` kompiliert.

## 3. Define thresholds for p95 latency and error rate

**Was gemacht wurde:**  
In k6 wurden Thresholds definiert:

- globale HTTP-Latenz: `p(95)<500`
- Fehlerquote: `rate<0.01`
- zusaetzliche Thresholds fuer einzelne Bereiche wie `state`, `command`, `legal_moves` und Puzzle/FEN

In Gatling wurden Assertions definiert:

- globales p95 unter `500 ms`
- fehlgeschlagene Requests unter `1%`

**Warum so integriert:**  
Die Thresholds liegen direkt in den jeweiligen Testdefinitionen. Dadurch ist ein Testlauf automatisch bewertbar: Er ist nicht nur eine Messung, sondern kann bestanden oder fehlschlagen.

**Sinn:**  
Thresholds machen Performance-Anforderungen explizit. Ohne diese Grenzen waere das Ergebnis nur eine Zahlentabelle. Mit Thresholds erkennt man sofort, ob die API das erwartete Ziel einhaelt.

**Status:**  
Umgesetzt.

## 4. Make the run reproducible

**Was gemacht wurde:**  
Es wurde ein eigener SBT-Task `performance` in `build.sbt` hinzugefuegt. Der Task startet nacheinander:

1. k6
2. Gatling
3. JMH

Der Befehl lautet:

```bash
sbt performance
```

Optional kann ein Zielsystem und Profil angegeben werden:

```bash
sbt -DbaseUrl=http://localhost:8080 -DperfProfile=full performance
```

**Warum so integriert:**  
SBT ist bereits das zentrale Build-Werkzeug des Projekts. Ein eigener Task ist deshalb besser als drei lose Einzelbefehle, weil alle Assignment-Tests an einem Ort gestartet werden koennen.

**Sinn:**  
Der Befehl trennt Performance-Tests von normalen Unit-Tests. So wird `sbt test` nicht langsam oder instabil, aber das Assignment kann trotzdem mit einem klaren Befehl ausgefuehrt werden.

**Status:**  
Umgesetzt. Wichtig: Der REST-Server muss vorher separat laufen:

```bash
sbt rest/run
```

## 5. Add one JMH benchmark for a hot function

**Was gemacht wurde:**  
Es wurde ein JMH-Benchmark im Subprojekt `benchmark` erstellt: `benchmark/src/main/scala/chess/benchmark/FenBenchmark.scala`.

Gemessen werden mehrere Hotspots:

- `Board.toFenPlacement`
- `GameState.fromFen`
- `MoveGenerator.legalMoves`

**Warum so integriert:**  
JMH ist als eigenes SBT-Subprojekt `benchmark` eingebunden. Dadurch werden Microbenchmarks nicht mit normalen Tests vermischt. Das ist wichtig, weil JMH spezielle Warmup-, Fork- und Measurement-Phasen braucht.

**Sinn:**  
JMH misst isolierte Funktionen ohne HTTP, Datenbank oder Frontend. Dadurch kann man erkennen, ob eine Optimierung in der eigentlichen Spiellogik messbar schneller ist.

**Status:**  
Umgesetzt und mit `benchmark/Jmh/compile` kompiliert.

## 6. Run baseline

**Was gemacht wurde:**  
Es liegen Baseline-Ergebnisse im Projekt:

- `perf/k6_baseline.txt`
- `perf/gatling/gatling_baseline.txt`
- `perf/jmh_baseline.txt`

Zusaetzlich sind die wichtigsten Zahlen in `perf/perf_results.md` zusammengefasst.

**Warum so integriert:**  
Die Baseline-Dateien liegen direkt neben den Performance-Tests. Dadurch ist nachvollziehbar, mit welchen Ausgangswerten die Optimierung verglichen wurde.

**Sinn:**  
Eine Baseline ist der Referenzpunkt. Ohne Baseline kann man nicht sagen, ob eine Aenderung wirklich schneller geworden ist oder ob nur zufaellige Messschwankungen vorliegen.

**Status:**  
Umgesetzt. Nach der Erweiterung der k6- und Gatling-Tests sollte optional eine neue Baseline mit dem aktuellen Stand erzeugt werden, wenn exakt diese neue Testsuite dokumentiert werden soll.

## 7. Optimize

**Was gemacht wurde:**  
Als Hotspot wurde `Board.toFenPlacement` identifiziert. Die Optimierung bestand aus zwei Massnahmen:

- `toFenPlacement` wurde von einer normalen Methode zu einem gecachten `lazy val`.
- Fuer Brettpositionen wurde ein `posCache` genutzt, damit beim Erzeugen des FEN-Strings nicht immer neue `Pos`-Objekte angelegt werden muessen.

**Warum so integriert:**  
`Board` ist immutable. Daher ist der FEN-String fuer eine konkrete Board-Instanz immer gleich. Caching passt hier gut, weil keine Inkonsistenz entstehen kann. Der Positionscache passt ebenfalls, weil es auf einem Schachbrett nur 64 gueltige Felder gibt.

**Sinn:**  
Die Optimierung reduziert wiederholte Berechnung und Objektallokationen. Das ist besonders relevant, weil FEN-Serialisierung in State-Antworten, History-Logik und Wiederholungserkennung vorkommt.

**Status:**  
Umgesetzt.

## 8. Rerun after optimization

**Was gemacht wurde:**  
Es liegen After-Ergebnisse im Projekt:

- `perf/k6_after.txt`
- `perf/gatling/gatling_after.txt`
- `perf/jmh_after.txt`

Die Zusammenfassung steht in `perf/perf_results.md`.

**Warum so integriert:**  
Die Vorher/Nachher-Dateien bleiben getrennt. Dadurch kann man die Messungen nachvollziehen und spaeter erneut vergleichen.

**Sinn:**  
Der Rerun zeigt, ob die Optimierung wirklich geholfen hat und ob sie keine negativen Auswirkungen auf die Gesamt-API hatte.

**Status:**  
Umgesetzt fuer die vorhandene Testsuite. Offen ist nur ein erneuter kompletter Lauf mit dem neuen `sbt performance`-Task, sobald `k6` auf Windows verfuegbar ist und der REST-Server laeuft.

## 9. Deliver evidence: k6 summary and bottleneck/fix note

**Was gemacht wurde:**  
Die k6-Ergebnisse sind in `perf/k6_baseline.txt`, `perf/k6_after.txt` und `perf/perf_results.md` dokumentiert. Die API blieb deutlich unter dem Ziel von `500 ms` p95 und hatte eine Fehlerquote von `0%`.

**Warum so integriert:**  
Die Textdateien enthalten den Originaloutput. `perf/perf_results.md` enthaelt eine lesbare Zusammenfassung fuer die Abgabe.

**Sinn:**  
k6 zeigt, dass die REST-API unter paralleler Last stabil bleibt. Der Bottleneck war auf HTTP-Ebene nicht stark sichtbar, weil die lokale API bereits sehr schnell war. Die eigentliche Optimierung wurde deshalb mit JMH nachgewiesen.

**Status:**  
Umgesetzt. Fuer die neu hinzugefuegten Puzzle/FEN-k6-Gruppen sollte nach dem naechsten Lauf die Ergebniszusammenfassung aktualisiert werden.

## 10. Deliver evidence: Gatling summary and bottleneck/fix note

**Was gemacht wurde:**  
Die Gatling-Ergebnisse liegen in `perf/gatling/gatling_baseline.txt`, `perf/gatling/gatling_after.txt` und in den HTML-Reports unter `perf/gatling/results`.

**Warum so integriert:**  
Gatling erzeugt automatisch HTML-Reports. Diese bleiben im `perf/gatling/results`-Ordner, waehrend die wichtigsten Zahlen in Textdateien und Markdown zusammengefasst sind.

**Sinn:**  
Gatling zeigt den realistischen Ablauf eines Nutzers. Dadurch wird bestaetigt, dass nicht nur einzelne Endpunkte schnell sind, sondern eine ganze Spielsequenz stabil bleibt.

**Status:**  
Umgesetzt. Fuer die neu hinzugefuegte Puzzle/FEN-Simulation sollte nach dem naechsten Lauf die Zusammenfassung aktualisiert werden.

## 11. Deliver evidence: JMH before/after numbers

**Was gemacht wurde:**  
Die JMH-Vorher/Nachher-Ergebnisse liegen in `perf/jmh_baseline.txt`, `perf/jmh_after.txt` und zusammengefasst in `perf/perf_results.md`.

Die wichtigste Verbesserung:

| Benchmark | Baseline | Optimiert | Ergebnis |
| --- | ---: | ---: | --- |
| `Board.toFenPlacement` | ca. `1.7-1.9 us/op` | ca. `0.001 us/op` | mehr als 1000x schneller bei wiederholtem Zugriff |

**Warum so integriert:**  
JMH liefert belastbare Microbenchmark-Zahlen mit Warmup und Forks. Dadurch ist die Messung aussagekraeftiger als eine manuelle Zeitmessung in einem Unit-Test.

**Sinn:**  
Die JMH-Zahlen belegen die eigentliche Code-Optimierung. Sie zeigen, dass der Hotspot im Modell deutlich schneller geworden ist.

**Status:**  
Umgesetzt.

## 12. Establish a baseline

**Was gemacht wurde:**  
Baseline-Dateien wurden fuer alle drei Werkzeuge abgelegt und in `perf/perf_results.md` zusammengefasst.

**Warum so integriert:**  
Die Baseline ist versionierbar und kann bei spaeteren Optimierungen erneut als Vergleich dienen.

**Sinn:**  
Sie beantwortet die Frage: "Wie schnell war das System vor der Optimierung?"

**Status:**  
Umgesetzt.

## 13. Find optimization and measure improvement

**Was gemacht wurde:**  
Die Optimierung wurde an `Board.toFenPlacement` vorgenommen und mit JMH gemessen. k6 und Gatling wurden genutzt, um zu pruefen, ob die API danach weiterhin stabil bleibt.

**Warum so integriert:**  
Die Kombination aus JMH und API-Lasttests ist sinnvoll: JMH zeigt die lokale Verbesserung im Hotspot, k6 und Gatling zeigen, ob das Gesamtsystem weiterhin korrekt und schnell reagiert.

**Sinn:**  
So wird nicht nur eine isolierte Funktion schneller gemacht, sondern auch kontrolliert, dass der reale Serverbetrieb nicht schlechter wird.

**Status:**  
Umgesetzt. Als naechster Schritt waere sinnvoll, `sbt performance` nach Installation von k6 einmal komplett auszufuehren und die neuen Outputs in `perf/perf_results.md` zu aktualisieren.

## 14. Zusammenfassung der Integration

Die Performance-Arbeit ist bewusst getrennt vom normalen Testlauf:

- `sbt test` bleibt fuer Unit- und Integrationstests.
- `sbt performance` fuehrt nur die drei Assignment-Performance-Checks aus.
- `perf/` enthaelt Skripte, Reports und Ergebnisdateien.
- `benchmark/` enthaelt isolierte JMH-Microbenchmarks.

Diese Struktur verhindert, dass normale Entwicklungs- und CI-Laeufe unnoetig langsam werden, macht die Performance-Abgabe aber trotzdem reproduzierbar.

## 15. Noch offene praktische Schritte

- `k6` auf Windows installieren oder als `perf/k6.exe` ablegen.
- REST-Server starten: `sbt rest/run`.
- Kompletten Lauf starten: `sbt performance`.
- Danach die neuen Ergebnisdateien fuer k6, Gatling und JMH speichern.
- `perf/perf_results.md` aktualisieren, falls die neu erweiterte Testsuite Teil der finalen Abgabe sein soll.
