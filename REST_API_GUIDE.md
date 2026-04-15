# REST API Implementierungsguide (Javi)

Dieser Guide erklaert, wie die REST API im Projekt aufgebaut ist, wie ein Request durch das System laeuft und wie du sie sicher erweitern kannst.

## 1) Zielbild und Architektur

Die REST API ist eine HTTP-Schicht ueber der bestehenden MVC/Funktions-Logik.

- HTTP/JSON Layer: `src/main/scala/chess/view/RestApi.scala`
- Command Parsing: `src/main/scala/chess/view/CommandParser.scala`
- Spielzustand/Regeln: `GameController.eval(...)` in der Controller-Schicht
- Start/Bootstrap: `src/main/scala/chess/Main.scala`

Wichtig: Die API fuehrt keine eigene Spiellogik aus. Sie transformiert nur HTTP Requests in `Command`-Objekte und gibt den aktuellen `AppState` als JSON zurueck.

## 2) Server-Start und Lifecycle

In `src/main/scala/chess/Main.scala` wird die API beim Programmstart registriert und gestartet:

1. `val restApi = new RestApi()`
2. `GameController.addObserver(restApi)`
3. `restApi.start(8080)`

`RestApi` implementiert `Observer[AppState]`. Bei jedem State-Update wird `currentState` (AtomicReference) aktualisiert. Dadurch kann `GET /api/state` jederzeit den letzten konsistenten Snapshot liefern.

## 3) Kernbausteine in `RestApi.scala`

### 3.1 Datenmodelle

- `CommandRequest(command: String)`
- `GameStateResponse(...)` (enthalt FEN, PGN, Clock, Capture-Infos, Parser-Infos, etc.)

JSON (De-)Serialisierung erfolgt per Circe (`deriveCodec`).

### 3.2 Routing

Die API hat ein zentrales `route` mit:

- CORS Header fuer Browser/GUI Zugriff
- `OPTIONS` fuer Preflight
- `pathPrefix("api")` fuer alle API-Endpunkte
- Fallback `404 Resource not found`

### 3.3 Endpunkte

#### `GET /api/state`

Liest den aktuellen `AppState` aus `currentState`, baut `GameStateResponse` und liefert JSON.

Nutzen:
- Polling durch GUI/WebClient
- Debugging (FEN/PGN/History/Clock/Material in einer Antwort)

#### `POST /api/command`

Verarbeitet Kommandos in zwei Formaten:

- JSON-Objekt: `{ "command": "e2e4" }`
- Raw-String (Fallback): `"e2e4"` oder `e2e4`

Ablauf:
1. `normalizeCommandJson(...)` normalisiert den Payload
2. `CommandParser.parse(...)` mappt Text auf `Command`
3. `GameController.eval(cmd)` wendet den Command an
4. HTTP-Status:
   - `400` bei `MessageType.Error`
   - `201` fuer `new`/`start`
   - `200` fuer erfolgreiche Standardkommandos

#### `GET /api/legal-moves?square=e2`

- validiert `square` via `Pos.fromAlgebraic`
- liefert Liste legaler Ziel-Felder aus `MoveGenerator.legalMovesFrom(...)`
- bei ungueltigem Feld: `400`

## 4) Request-Flow Ende-zu-Ende

Beispiel `POST /api/command` mit `e2e4`:

1. HTTP Request kommt in `RestApi.route` an
2. Payload wird normalisiert (`normalizeCommandJson`)
3. Parser erzeugt `Command.ApplyMove(...)` oder `Command.Unknown(...)`
4. `GameController.eval` erzeugt neuen `AppState`
5. Controller benachrichtigt Observer
6. `RestApi.update(state)` speichert den neuen Snapshot
7. Naechster `GET /api/state` liefert den aktualisierten Zustand

## 5) Beispielaufrufe (curl)

```bash
curl -s http://localhost:8080/api/state
```

```bash
curl -s -X POST http://localhost:8080/api/command \
  -H 'Content-Type: application/json' \
  -d '{"command":"e2e4"}'
```

```bash
curl -s 'http://localhost:8080/api/legal-moves?square=e2'
```

## 6) Wie du einen neuen Endpunkt implementierst

Empfohlenes Vorgehen:

1. **API-Vertrag festlegen**
   - Pfad, Methode, Query/Body, Response, Fehlercodes
2. **DTO anlegen**
   - neue Case Class + Circe Codec (falls Body/Response noetig)
3. **Route in `RestApi.route` ergaenzen**
   - moeglichst unter `pathPrefix("api")`
4. **Bestehende Logik nutzen**
   - Parsing in `CommandParser`
   - Zustandsaenderung in `GameController.eval`
5. **Fehlerkonvention einhalten**
   - `400` fuer Input-/Domain-Fehler
   - `404` fuer unbekannte Ressourcen
6. **Tests ergaenzen**
   - API-Routentests + parser/controller-bezogene Regressionstests

## 7) Designentscheidungen und Trade-offs

- **Observer + AtomicReference**
  - sehr einfach fuer read-last-state
  - gut fuer Polling Clients
- **Command-zentrierte API**
  - wenig Duplikation gegenueber TUI/GUI
  - Einheitliche Regeln im Controller
- **Raw-String-Fallback in `/api/command`**
  - robust gegen einfache Clients
  - birgt etwas mehr Parsing-Komplexitaet

## 8) Typische Fehlerbilder

- `400 Invalid square`: Query-Parameter fuer legal-moves ungueltig
- `400 <Fehlermeldung>` nach Command: Domain-Regel verletzt (z. B. ungueltiger Zug)
- `404 Resource not found`: falscher Pfad ausserhalb von `/api/...`

## 9) Quick-Check fuer Erklaerungen im Team

Wenn du die Implementierung praesentierst, fuehre durch diese Reihenfolge:

1. `Main.setup` (Serverstart + Observer-Registrierung)
2. `RestApi.route` (API-Oberflaeche)
3. `POST /api/command` (Kernfluss)
4. `CommandParser` und `GameController.eval` (Business-Pfad)
5. `GET /api/state` (State-Read fuer Clients)

So verstehen Zuhorer schnell: API ist duenne Transport-Schicht, Spiellogik bleibt zentral im Controller/Model.

