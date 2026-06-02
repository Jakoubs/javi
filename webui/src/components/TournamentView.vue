<script setup>
import { computed, onUnmounted, ref } from 'vue'
import ChessBoard from './ChessBoard.vue'

const DEFAULT_TOURNAMENT_SERVER = 'https://st.nowchess.janis-eccarius.de'

const serverUrl = ref(localStorage.getItem('tournamentServerUrl') || DEFAULT_TOURNAMENT_SERVER)
const token = ref(localStorage.getItem('tournamentBearerToken') || '')
const selectedTournamentId = ref(localStorage.getItem('tournamentId') || '')
const selectedGameId = ref(localStorage.getItem('tournamentGameId') || '')
const moveInput = ref('')
const statusMessage = ref('')
const statusIsError = ref(false)
const loading = ref(false)

const tournaments = ref({ created: [], started: [], finished: [] })
const selectedTournament = ref(null)
const results = ref([])
const roundInfo = ref(null)
const roundInput = ref(1)
const gameState = ref(null)
const tournamentEvents = ref([])
const gameEvents = ref([])

const createForm = ref({
  name: 'JAVI Bot Arena',
  nbRounds: 3,
  clockLimit: 300,
  clockIncrement: 3,
  rated: true,
  format: 'swiss',
  startPosition: 'standard',
  matchesPerPairing: 1,
  groupSize: ''
})
const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'

let tournamentStreamAbort = null
let gameStreamAbort = null

const normalizedBaseUrl = computed(() => serverUrl.value.trim().replace(/\/$/, ''))
const authHeaders = computed(() => {
  const headers = {}
  if (token.value.trim()) headers.Authorization = `Bearer ${token.value.trim()}`
  return headers
})
const flatTournaments = computed(() => [
  ...tournaments.value.created.map(t => ({ ...t, statusGroup: 'created' })),
  ...tournaments.value.started.map(t => ({ ...t, statusGroup: 'started' })),
  ...tournaments.value.finished.map(t => ({ ...t, statusGroup: 'finished' }))
])
const currentFen = computed(() => gameState.value?.fen || START_FEN)

const setStatus = (message, isError = false) => {
  statusMessage.value = message
  statusIsError.value = isError
}

const saveConfig = () => {
  localStorage.setItem('tournamentServerUrl', normalizedBaseUrl.value)
  localStorage.setItem('tournamentBearerToken', token.value.trim())
  if (selectedTournamentId.value) localStorage.setItem('tournamentId', selectedTournamentId.value)
  if (selectedGameId.value) localStorage.setItem('tournamentGameId', selectedGameId.value)
}

const request = async (path, options = {}) => {
  const response = await fetch(`${normalizedBaseUrl.value}${path}`, {
    ...options,
    headers: {
      ...authHeaders.value,
      ...(options.headers || {})
    }
  })

  if (!response.ok) {
    const text = await response.text()
    let detail = text
    try {
      const json = JSON.parse(text)
      detail = json.error || json.message || text
    } catch (e) {}
    throw new Error(detail || `${response.status} ${response.statusText}`)
  }

  return response
}

const readJson = async (path, options = {}) => {
  const response = await request(path, options)
  return response.json()
}

const readNdjson = async (path, options = {}) => {
  const response = await request(path, {
    ...options,
    headers: {
      Accept: 'application/x-ndjson',
      ...(options.headers || {})
    }
  })
  const text = await response.text()
  return text
    .split('\n')
    .map(line => line.trim())
    .filter(Boolean)
    .map(line => JSON.parse(line))
}

const loadTournaments = async () => {
  loading.value = true
  try {
    saveConfig()
    tournaments.value = await readJson('/api/tournament')
    setStatus('Tournament server connected.')
  } catch (e) {
    setStatus(`Connection failed: ${e.message}`, true)
  } finally {
    loading.value = false
  }
}

const selectTournament = async (id) => {
  selectedTournamentId.value = id
  saveConfig()
  await loadTournament()
}

const loadTournament = async () => {
  if (!selectedTournamentId.value.trim()) return
  loading.value = true
  try {
    selectedTournament.value = await readJson(`/api/tournament/${encodeURIComponent(selectedTournamentId.value.trim())}`)
    setStatus('Tournament loaded.')
  } catch (e) {
    setStatus(`Tournament load failed: ${e.message}`, true)
  } finally {
    loading.value = false
  }
}

const createTournament = async () => {
  loading.value = true
  try {
    saveConfig()
    const body = new URLSearchParams()
    Object.entries(createForm.value).forEach(([key, value]) => {
      if (value !== '' && value !== null && value !== undefined) body.set(key, String(value))
    })
    const tournament = await readJson('/api/tournament', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body
    })
    selectedTournament.value = tournament
    selectedTournamentId.value = tournament.id
    saveConfig()
    await loadTournaments()
    setStatus(`Created tournament ${tournament.id}.`)
  } catch (e) {
    setStatus(`Create failed: ${e.message}`, true)
  } finally {
    loading.value = false
  }
}

const joinTournament = async () => {
  if (!selectedTournamentId.value.trim()) return
  loading.value = true
  try {
    await readJson(`/api/tournament/${encodeURIComponent(selectedTournamentId.value.trim())}/join`, { method: 'POST' })
    await loadTournament()
    setStatus('Joined tournament as authenticated bot.')
  } catch (e) {
    setStatus(`Join failed: ${e.message}`, true)
  } finally {
    loading.value = false
  }
}

const withdrawTournament = async () => {
  if (!selectedTournamentId.value.trim()) return
  loading.value = true
  try {
    await readJson(`/api/tournament/${encodeURIComponent(selectedTournamentId.value.trim())}/withdraw`, { method: 'POST' })
    await loadTournament()
    setStatus('Withdrawn from tournament.')
  } catch (e) {
    setStatus(`Withdraw failed: ${e.message}`, true)
  } finally {
    loading.value = false
  }
}

const startTournament = async () => {
  if (!selectedTournamentId.value.trim()) return
  loading.value = true
  try {
    selectedTournament.value = await readJson(`/api/tournament/${encodeURIComponent(selectedTournamentId.value.trim())}/start`, { method: 'POST' })
    setStatus('Tournament started.')
  } catch (e) {
    setStatus(`Start failed: ${e.message}`, true)
  } finally {
    loading.value = false
  }
}

const loadResults = async () => {
  if (!selectedTournamentId.value.trim()) return
  loading.value = true
  try {
    results.value = await readNdjson(`/api/tournament/${encodeURIComponent(selectedTournamentId.value.trim())}/results`)
    setStatus('Results loaded.')
  } catch (e) {
    setStatus(`Results failed: ${e.message}`, true)
  } finally {
    loading.value = false
  }
}

const loadRound = async () => {
  if (!selectedTournamentId.value.trim()) return
  loading.value = true
  try {
    roundInfo.value = await readJson(`/api/tournament/${encodeURIComponent(selectedTournamentId.value.trim())}/round/${roundInput.value}`)
    setStatus(`Round ${roundInput.value} loaded.`)
  } catch (e) {
    setStatus(`Round failed: ${e.message}`, true)
  } finally {
    loading.value = false
  }
}

const loadGame = async () => {
  if (!selectedTournamentId.value.trim() || !selectedGameId.value.trim()) return
  loading.value = true
  try {
    saveConfig()
    gameState.value = await readJson(`/api/tournament/${encodeURIComponent(selectedTournamentId.value.trim())}/game/${encodeURIComponent(selectedGameId.value.trim())}`)
    setStatus('Game state loaded.')
  } catch (e) {
    setStatus(`Game load failed: ${e.message}`, true)
  } finally {
    loading.value = false
  }
}

const sendMove = async () => {
  const uci = moveInput.value.trim()
  if (!selectedTournamentId.value.trim() || !selectedGameId.value.trim() || !uci) return
  loading.value = true
  try {
    await readJson(`/api/tournament/${encodeURIComponent(selectedTournamentId.value.trim())}/game/${encodeURIComponent(selectedGameId.value.trim())}/move/${encodeURIComponent(uci)}`, { method: 'POST' })
    moveInput.value = ''
    await loadGame()
    setStatus(`Move ${uci} accepted.`)
  } catch (e) {
    setStatus(`Move failed: ${e.message}`, true)
  } finally {
    loading.value = false
  }
}

const streamLines = async (path, sink, abortRefSetter) => {
  const controller = new AbortController()
  abortRefSetter(controller)
  const response = await request(path, {
    signal: controller.signal,
    headers: { Accept: 'application/x-ndjson' }
  })
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    lines.map(line => line.trim()).filter(Boolean).forEach(line => {
      const event = JSON.parse(line)
      sink.value.unshift(event)
      if (event.gameId) selectedGameId.value = event.gameId
      if (event.type === 'gameState') gameState.value = event
      if (event.fen && gameState.value) gameState.value = { ...gameState.value, fen: event.fen, turn: event.turn, clock: event.clock }
    })
  }
}

const startTournamentStream = async () => {
  if (!selectedTournamentId.value.trim()) return
  stopTournamentStream()
  tournamentEvents.value = []
  try {
    await streamLines(
      `/api/tournament/${encodeURIComponent(selectedTournamentId.value.trim())}/stream`,
      tournamentEvents,
      controller => { tournamentStreamAbort = controller }
    )
  } catch (e) {
    if (e.name !== 'AbortError') setStatus(`Tournament stream stopped: ${e.message}`, true)
  }
}

const stopTournamentStream = () => {
  tournamentStreamAbort?.abort()
  tournamentStreamAbort = null
}

const startGameStream = async () => {
  if (!selectedTournamentId.value.trim() || !selectedGameId.value.trim()) return
  stopGameStream()
  gameEvents.value = []
  try {
    await streamLines(
      `/api/tournament/${encodeURIComponent(selectedTournamentId.value.trim())}/game/${encodeURIComponent(selectedGameId.value.trim())}/stream`,
      gameEvents,
      controller => { gameStreamAbort = controller }
    )
  } catch (e) {
    if (e.name !== 'AbortError') setStatus(`Game stream stopped: ${e.message}`, true)
  }
}

const stopGameStream = () => {
  gameStreamAbort?.abort()
  gameStreamAbort = null
}

onUnmounted(() => {
  stopTournamentStream()
  stopGameStream()
})
</script>

<template>
  <div class="tournament-view">
    <section class="tournament-panel glass">
      <div class="panel-title-row">
        <div>
          <h2>Tournament Server</h2>
          <p>Connect to the NowChess-compatible bot tournament API.</p>
        </div>
        <span :class="['tournament-status', { error: statusIsError }]">{{ statusMessage || 'Idle' }}</span>
      </div>

      <div class="connection-grid">
        <label>
          <span>Server URL</span>
          <input v-model="serverUrl" class="glass-input" spellcheck="false">
        </label>
        <label>
          <span>Bearer JWT</span>
          <input v-model="token" class="glass-input" type="password" spellcheck="false">
        </label>
        <button class="preset-btn primary" :disabled="loading" @click="loadTournaments">Connect</button>
      </div>
    </section>

    <div class="tournament-grid">
      <section class="tournament-panel glass">
        <div class="panel-title-row compact">
          <h3>Tournaments</h3>
          <button class="mini-btn" @click="loadTournaments">Refresh</button>
        </div>
        <div class="tournament-list">
          <button
            v-for="t in flatTournaments"
            :key="t.id"
            :class="['tournament-row', { selected: selectedTournamentId === t.id }]"
            @click="selectTournament(t.id)"
          >
            <span class="tournament-name">{{ t.fullName || t.name || t.id }}</span>
            <span class="tournament-meta">{{ t.statusGroup }} | {{ t.nbPlayers || 0 }} bots | {{ t.nbRounds }} rounds</span>
          </button>
          <div v-if="flatTournaments.length === 0" class="empty-state">No tournaments loaded.</div>
        </div>
      </section>

      <section class="tournament-panel glass">
        <h3>Create Tournament</h3>
        <div class="form-grid">
          <label><span>Name</span><input v-model="createForm.name" class="glass-input"></label>
          <label><span>Format</span>
            <select v-model="createForm.format" class="glass-input">
              <option value="swiss">swiss</option>
              <option value="singleElimination">singleElimination</option>
              <option value="doubleElimination">doubleElimination</option>
              <option value="groupStage">groupStage</option>
              <option value="league">league</option>
            </select>
          </label>
          <label><span>Rounds</span><input v-model.number="createForm.nbRounds" class="glass-input" type="number" min="1"></label>
          <label><span>Clock</span><input v-model.number="createForm.clockLimit" class="glass-input" type="number" min="1"></label>
          <label><span>Increment</span><input v-model.number="createForm.clockIncrement" class="glass-input" type="number" min="0"></label>
          <label><span>Matches</span><input v-model.number="createForm.matchesPerPairing" class="glass-input" type="number" min="1"></label>
          <label><span>Start FEN</span><input v-model="createForm.startPosition" class="glass-input"></label>
          <label><span>Group size</span><input v-model="createForm.groupSize" class="glass-input" type="number" min="2"></label>
        </div>
        <button class="preset-btn primary full-width" :disabled="loading" @click="createTournament">Create</button>
      </section>

      <section class="tournament-panel glass">
        <h3>Selected Tournament</h3>
        <div class="selected-controls">
          <input v-model="selectedTournamentId" class="glass-input mono" placeholder="Tournament ID" @keyup.enter="loadTournament">
          <button class="mini-btn" @click="loadTournament">Load</button>
          <button class="mini-btn" @click="joinTournament">Join</button>
          <button class="mini-btn" @click="withdrawTournament">Withdraw</button>
          <button class="mini-btn" @click="startTournament">Start</button>
        </div>
        <pre class="json-box">{{ selectedTournament ? JSON.stringify(selectedTournament, null, 2) : 'No tournament selected.' }}</pre>
      </section>

      <section class="tournament-panel glass">
        <h3>Round & Results</h3>
        <div class="selected-controls">
          <input v-model.number="roundInput" class="glass-input mono" type="number" min="1">
          <button class="mini-btn" @click="loadRound">Round</button>
          <button class="mini-btn" @click="loadResults">Results</button>
        </div>
        <pre class="json-box">{{ roundInfo ? JSON.stringify(roundInfo, null, 2) : JSON.stringify(results, null, 2) }}</pre>
      </section>

      <section class="tournament-panel glass game-panel">
        <div class="panel-title-row compact">
          <h3>Game</h3>
          <div class="stream-actions">
            <button class="mini-btn" @click="startTournamentStream">Tournament stream</button>
            <button class="mini-btn danger" @click="stopTournamentStream">Stop</button>
          </div>
        </div>
        <div class="game-layout">
          <div class="game-board-wrap">
            <ChessBoard :fen="currentFen" :flipped="false" />
          </div>
          <div class="game-tools">
            <input v-model="selectedGameId" class="glass-input mono" placeholder="Game ID" @keyup.enter="loadGame">
            <button class="mini-btn" @click="loadGame">Load game</button>
            <div class="move-row">
              <input v-model="moveInput" class="glass-input mono" placeholder="e2e4" @keyup.enter="sendMove">
              <button class="mini-btn primary" @click="sendMove">Send</button>
            </div>
            <div class="stream-actions">
              <button class="mini-btn" @click="startGameStream">Game stream</button>
              <button class="mini-btn danger" @click="stopGameStream">Stop</button>
            </div>
            <pre class="json-box small">{{ gameState ? JSON.stringify(gameState, null, 2) : 'No game loaded.' }}</pre>
          </div>
        </div>
      </section>

      <section class="tournament-panel glass">
        <h3>Events</h3>
        <pre class="json-box events">{{ JSON.stringify({ tournament: tournamentEvents.slice(0, 20), game: gameEvents.slice(0, 20) }, null, 2) }}</pre>
      </section>
    </div>
  </div>
</template>

<style scoped>
.tournament-view {
  width: 100%;
  height: 100%;
  overflow-y: auto;
  padding: 1rem;
  box-sizing: border-box;
}

.tournament-panel {
  padding: 1rem;
  border-radius: 12px;
  text-align: left;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.panel-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1rem;
}

.panel-title-row.compact {
  align-items: center;
}

h2, h3, p {
  margin: 0;
}

h2, h3 {
  color: var(--primary);
}

p {
  color: rgba(255, 255, 255, 0.6);
  font-size: 0.9rem;
}

.tournament-status {
  max-width: 360px;
  color: var(--primary);
  font-size: 0.85rem;
  text-align: right;
}

.tournament-status.error {
  color: #ff6b6b;
}

.connection-grid,
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 0.75rem;
  align-items: end;
}

label {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  color: rgba(255, 255, 255, 0.55);
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.tournament-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(320px, 1fr));
  gap: 1rem;
  margin-top: 1rem;
}

.tournament-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-height: 340px;
  overflow-y: auto;
}

.tournament-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.2rem;
  padding: 0.75rem;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  color: white;
}

.tournament-row.selected {
  border-color: var(--primary);
  background: rgba(78, 204, 163, 0.14);
}

.tournament-name {
  font-weight: 700;
}

.tournament-meta,
.empty-state {
  color: rgba(255, 255, 255, 0.55);
  font-size: 0.8rem;
}

.selected-controls,
.stream-actions,
.move-row {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
  align-items: center;
}

.selected-controls {
  margin-bottom: 0.75rem;
}

.mini-btn {
  border-radius: 8px;
  padding: 0.55rem 0.75rem;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.06);
  color: white;
  font-size: 0.85rem;
}

.mini-btn.primary,
.preset-btn.primary {
  border-color: rgba(78, 204, 163, 0.3);
  background: rgba(78, 204, 163, 0.16);
  color: var(--primary);
}

.mini-btn.danger {
  border-color: rgba(255, 107, 107, 0.4);
  color: #ff6b6b;
}

.full-width {
  width: 100%;
  justify-content: center;
  margin-top: 0.75rem;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.json-box {
  min-height: 180px;
  max-height: 360px;
  overflow: auto;
  padding: 0.75rem;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.24);
  color: rgba(255, 255, 255, 0.78);
  font-size: 0.78rem;
  white-space: pre-wrap;
}

.json-box.small {
  max-height: 260px;
}

.json-box.events {
  max-height: 520px;
}

.game-panel {
  grid-column: span 2;
}

.game-layout {
  display: grid;
  grid-template-columns: minmax(280px, 420px) 1fr;
  gap: 1rem;
}

.game-board-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 420px;
  overflow: hidden;
}

.game-tools {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

@media (max-width: 900px) {
  .tournament-grid,
  .game-layout {
    grid-template-columns: 1fr;
  }

  .game-panel {
    grid-column: span 1;
  }
}
</style>
