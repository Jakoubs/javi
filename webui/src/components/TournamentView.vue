<script setup>
import { computed, onUnmounted, ref } from 'vue'
import ChessBoard from './ChessBoard.vue'

const DEFAULT_TOURNAMENT_SERVER = '/tournament-api'

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

const activeTab = ref('connect')
const showJsonModal = ref(false)
const jsonModalTitle = ref('')
const jsonModalContent = ref('')

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

const openJsonModal = (title, data) => {
  jsonModalTitle.value = title
  jsonModalContent.value = JSON.stringify(data, null, 2)
  showJsonModal.value = true
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
    activeTab.value = 'tournament'
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
    activeTab.value = 'tournament'
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
    openJsonModal('Results', results.value)
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
    openJsonModal(`Round ${roundInput.value}`, roundInfo.value)
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

    <!-- Status bar -->
    <div class="status-bar">
      <div class="status-left">
        <span class="connected-dot" :class="{ active: flatTournaments.length > 0 }"></span>
        <span class="server-label">{{ normalizedBaseUrl }}</span>
      </div>
      <span :class="['status-msg', { error: statusIsError }]">{{ statusMessage }}</span>
      <span v-if="loading" class="spinner"></span>
    </div>

    <!-- Tabs -->
    <div class="tabs">
      <button
        v-for="tab in [
          { id: 'connect', label: 'Connect' },
          { id: 'create', label: 'Create' },
          { id: 'tournament', label: 'Tournament' },
          { id: 'game', label: 'Game' }
        ]"
        :key="tab.id"
        :class="['tab-btn', { active: activeTab === tab.id }]"
        @click="activeTab = tab.id"
      >{{ tab.label }}</button>
    </div>

    <!-- ── Tab: Connect ── -->
    <div v-if="activeTab === 'connect'" class="tab-panel glass">
      <h3>Tournament Server</h3>
      <p class="hint">Connect to a NowChess-compatible bot tournament API.</p>
      <div class="field-row mt">
        <label>
          <span>Server URL</span>
          <input v-model="serverUrl" class="glass-input" spellcheck="false">
        </label>
        <label>
          <span>Bearer JWT</span>
          <input v-model="token" class="glass-input" type="password" spellcheck="false">
        </label>
        <button class="btn primary" :disabled="loading" @click="loadTournaments">Connect &amp; Load</button>
      </div>
    </div>

    <!-- ── Tab: Create ── -->
    <div v-if="activeTab === 'create'" class="tab-panel glass">
      <h3>Create Tournament</h3>
      <div class="create-grid mt">
        <label>
          <span>Name</span>
          <input v-model="createForm.name" class="glass-input">
        </label>
        <label>
          <span>Format</span>
          <select v-model="createForm.format" class="glass-input">
            <option value="swiss">Swiss</option>
            <option value="singleElimination">Single Elimination</option>
            <option value="doubleElimination">Double Elimination</option>
            <option value="groupStage">Group Stage</option>
            <option value="league">League</option>
          </select>
        </label>
        <label>
          <span>Rounds</span>
          <input v-model.number="createForm.nbRounds" class="glass-input" type="number" min="1">
        </label>
        <label>
          <span>Clock (s)</span>
          <input v-model.number="createForm.clockLimit" class="glass-input" type="number" min="1">
        </label>
        <label>
          <span>Increment (s)</span>
          <input v-model.number="createForm.clockIncrement" class="glass-input" type="number" min="0">
        </label>
        <label>
          <span>Matches/Pairing</span>
          <input v-model.number="createForm.matchesPerPairing" class="glass-input" type="number" min="1">
        </label>
        <label>
          <span>Start FEN</span>
          <input v-model="createForm.startPosition" class="glass-input">
        </label>
        <label>
          <span>Group size</span>
          <input v-model="createForm.groupSize" class="glass-input" type="number" min="2">
        </label>
      </div>
      <label class="checkbox-row mt">
        <input type="checkbox" v-model="createForm.rated"> Rated
      </label>
      <button class="btn primary full-width mt" :disabled="loading" @click="createTournament">Create Tournament</button>
    </div>

    <!-- ── Tab: Tournament ── -->
    <div v-if="activeTab === 'tournament'" class="tab-panel">
      <div class="tournament-split">

        <!-- Left: list -->
        <section class="glass side-panel">
          <div class="panel-header">
            <h3>Tournaments</h3>
            <button class="btn-icon" title="Refresh" @click="loadTournaments">↻</button>
          </div>
          <div class="t-list">
            <button
              v-for="t in flatTournaments"
              :key="t.id"
              :class="['t-row', { selected: selectedTournamentId === t.id }]"
              @click="selectTournament(t.id)"
            >
              <span class="t-name">{{ t.fullName || t.name || t.id }}</span>
              <span class="t-meta">{{ t.statusGroup }} · {{ t.nbPlayers || 0 }} bots · {{ t.nbRounds }}r</span>
            </button>
            <div v-if="flatTournaments.length === 0" class="empty">No tournaments loaded.</div>
          </div>
        </section>

        <!-- Right: detail -->
        <section class="glass main-panel">
          <div class="panel-header">
            <h3>Selected</h3>
            <div class="btn-group">
              <input v-model="selectedTournamentId" class="glass-input mono small" placeholder="ID" @keyup.enter="loadTournament">
              <button class="mini-btn" @click="loadTournament">Load</button>
              <button class="mini-btn" @click="joinTournament">Join</button>
              <button class="mini-btn" @click="withdrawTournament">Withdraw</button>
              <button class="mini-btn primary" @click="startTournament">Start</button>
            </div>
          </div>

          <!-- Tournament info chips -->
          <div v-if="selectedTournament" class="info-chips">
            <span class="chip">{{ selectedTournament.status }}</span>
            <span class="chip">{{ selectedTournament.system || selectedTournament.format }}</span>
            <span class="chip">{{ selectedTournament.nbPlayers || 0 }} players</span>
            <span class="chip">{{ selectedTournament.nbRounds }} rounds</span>
            <button class="chip-link" @click="openJsonModal('Tournament', selectedTournament)">View JSON ↗</button>
          </div>
          <div v-else class="empty mt">No tournament selected.</div>

          <!-- Round & Results -->
          <div class="section-divider">
            <span>Round &amp; Results</span>
          </div>
          <div class="btn-group">
            <input v-model.number="roundInput" class="glass-input mono small" type="number" min="1" style="width:4rem">
            <button class="mini-btn" @click="loadRound">Load Round</button>
            <button class="mini-btn" @click="loadResults">Load Results</button>
          </div>

          <!-- Stream -->
          <div class="section-divider">
            <span>Stream</span>
          </div>
          <div class="btn-group">
            <button class="mini-btn" @click="startTournamentStream">▶ Start</button>
            <button class="mini-btn danger" @click="stopTournamentStream">■ Stop</button>
            <button v-if="tournamentEvents.length" class="chip-link" @click="openJsonModal('Tournament Events', tournamentEvents.slice(0, 30))">
              {{ tournamentEvents.length }} events ↗
            </button>
          </div>
        </section>
      </div>
    </div>

    <!-- ── Tab: Game ── -->
    <div v-if="activeTab === 'game'" class="tab-panel">
      <div class="game-split">
        <!-- Board -->
        <div class="board-wrap glass">
          <ChessBoard :fen="currentFen" :flipped="false" />
        </div>

        <!-- Controls -->
        <div class="game-controls glass">
          <h3>Game</h3>

          <label class="mt">
            <span>Game ID</span>
            <div class="btn-group">
              <input v-model="selectedGameId" class="glass-input mono" placeholder="Game ID" @keyup.enter="loadGame">
              <button class="mini-btn" @click="loadGame">Load</button>
            </div>
          </label>

          <label class="mt">
            <span>Move (UCI)</span>
            <div class="btn-group">
              <input v-model="moveInput" class="glass-input mono" placeholder="e2e4" @keyup.enter="sendMove">
              <button class="mini-btn primary" @click="sendMove">Send</button>
            </div>
          </label>

          <!-- Game state chips -->
          <div v-if="gameState" class="info-chips mt">
            <span class="chip">{{ gameState.turn === 'w' ? 'White to move' : 'Black to move' }}</span>
            <span v-if="gameState.status" class="chip">{{ gameState.status }}</span>
            <button class="chip-link" @click="openJsonModal('Game State', gameState)">State JSON ↗</button>
          </div>

          <div class="section-divider mt">
            <span>Streams</span>
          </div>
          <div class="stream-row">
            <div class="stream-item">
              <span class="stream-label">Tournament</span>
              <div class="btn-group">
                <button class="mini-btn" @click="startTournamentStream">▶</button>
                <button class="mini-btn danger" @click="stopTournamentStream">■</button>
                <button v-if="tournamentEvents.length" class="chip-link" @click="openJsonModal('Tournament Events', tournamentEvents.slice(0, 30))">
                  {{ tournamentEvents.length }} ↗
                </button>
              </div>
            </div>
            <div class="stream-item">
              <span class="stream-label">Game</span>
              <div class="btn-group">
                <button class="mini-btn" @click="startGameStream">▶</button>
                <button class="mini-btn danger" @click="stopGameStream">■</button>
                <button v-if="gameEvents.length" class="chip-link" @click="openJsonModal('Game Events', gameEvents.slice(0, 30))">
                  {{ gameEvents.length }} ↗
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ── JSON Modal ── -->
    <Teleport to="body">
      <div v-if="showJsonModal" class="modal-backdrop" @click.self="showJsonModal = false">
        <div class="modal glass">
          <div class="modal-header">
            <span class="modal-title">{{ jsonModalTitle }}</span>
            <button class="btn-icon" @click="showJsonModal = false">✕</button>
          </div>
          <pre class="json-box">{{ jsonModalContent }}</pre>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/* ── Layout ── */
.tournament-view {
  width: 100%;
  height: 100%;
  overflow-y: auto;
  padding: 0.75rem 1rem 1.5rem;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

/* ── Status bar ── */
.status-bar {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.4rem 0.75rem;
  border-radius: 8px;
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.07);
  font-size: 0.8rem;
  flex-shrink: 0;
}

.status-left {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  flex: 1;
  min-width: 0;
}

.connected-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: rgba(255,255,255,0.2);
  flex-shrink: 0;
  transition: background 0.3s;
}
.connected-dot.active { background: var(--primary); box-shadow: 0 0 6px var(--primary); }

.server-label {
  color: rgba(255,255,255,0.4);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-msg {
  color: var(--primary);
  font-size: 0.78rem;
  flex-shrink: 0;
}
.status-msg.error { color: #ff6b6b; }

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.15);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
  flex-shrink: 0;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* ── Tabs ── */
.tabs {
  display: flex;
  gap: 0.25rem;
  flex-shrink: 0;
}

.tab-btn {
  padding: 0.45rem 1.1rem;
  border-radius: 8px;
  border: 1px solid rgba(255,255,255,0.08);
  background: rgba(255,255,255,0.04);
  color: rgba(255,255,255,0.55);
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.18s;
}
.tab-btn:hover { background: rgba(255,255,255,0.08); color: white; }
.tab-btn.active {
  border-color: rgba(78,204,163,0.4);
  background: rgba(78,204,163,0.12);
  color: var(--primary);
}

/* ── Glass panel ── */
.glass {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 12px;
}

.tab-panel {
  flex: 1;
  padding: 1rem;
}

.tab-panel.glass {
  /* for connect/create: padding already on tab-panel */
}

/* ── Typography ── */
h3 {
  margin: 0;
  color: var(--primary);
  font-size: 1rem;
}

.hint {
  color: rgba(255,255,255,0.45);
  font-size: 0.82rem;
  margin: 0.25rem 0 0;
}

/* ── Field helpers ── */
.mt { margin-top: 0.85rem; }

label {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  color: rgba(255,255,255,0.5);
  font-size: 0.73rem;
  text-transform: uppercase;
  letter-spacing: 0.07em;
}

.field-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 0.6rem;
  align-items: end;
}

.create-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(155px, 1fr));
  gap: 0.6rem;
}

.checkbox-row {
  flex-direction: row;
  align-items: center;
  gap: 0.4rem;
  color: rgba(255,255,255,0.65);
  font-size: 0.85rem;
  text-transform: none;
  letter-spacing: 0;
}

/* ── Buttons ── */
.btn {
  border-radius: 8px;
  padding: 0.55rem 1rem;
  border: 1px solid rgba(255,255,255,0.12);
  background: rgba(255,255,255,0.06);
  color: white;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.btn:hover { background: rgba(255,255,255,0.1); }
.btn.primary {
  border-color: rgba(78,204,163,0.35);
  background: rgba(78,204,163,0.14);
  color: var(--primary);
}
.btn.primary:hover { background: rgba(78,204,163,0.22); }
.btn.full-width { width: 100%; justify-content: center; }

.mini-btn {
  border-radius: 6px;
  padding: 0.35rem 0.65rem;
  border: 1px solid rgba(255,255,255,0.1);
  background: rgba(255,255,255,0.05);
  color: white;
  font-size: 0.8rem;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.mini-btn:hover { background: rgba(255,255,255,0.1); }
.mini-btn.primary {
  border-color: rgba(78,204,163,0.3);
  background: rgba(78,204,163,0.12);
  color: var(--primary);
}
.mini-btn.danger {
  border-color: rgba(255,107,107,0.35);
  color: #ff6b6b;
}

.btn-icon {
  background: none;
  border: none;
  color: rgba(255,255,255,0.4);
  font-size: 1rem;
  cursor: pointer;
  padding: 0.2rem 0.4rem;
  border-radius: 4px;
  transition: color 0.15s;
}
.btn-icon:hover { color: white; }

.btn-group {
  display: flex;
  gap: 0.4rem;
  flex-wrap: wrap;
  align-items: center;
}

/* ── Tournament split ── */
.tournament-split {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 0.75rem;
  height: 100%;
}

.side-panel, .main-panel {
  padding: 0.75rem;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.t-list {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  overflow-y: auto;
  flex: 1;
}

.t-row {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  padding: 0.5rem 0.6rem;
  border: 1px solid rgba(255,255,255,0.06);
  border-radius: 7px;
  background: rgba(255,255,255,0.03);
  color: white;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s;
}
.t-row:hover { background: rgba(255,255,255,0.07); }
.t-row.selected {
  border-color: var(--primary);
  background: rgba(78,204,163,0.1);
}

.t-name { font-size: 0.85rem; font-weight: 600; }
.t-meta { font-size: 0.72rem; color: rgba(255,255,255,0.45); }

.empty { color: rgba(255,255,255,0.35); font-size: 0.82rem; }

/* ── Info chips ── */
.info-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.chip {
  padding: 0.25rem 0.55rem;
  border-radius: 20px;
  background: rgba(255,255,255,0.08);
  border: 1px solid rgba(255,255,255,0.1);
  font-size: 0.75rem;
  color: rgba(255,255,255,0.7);
}

.chip-link {
  padding: 0.25rem 0.55rem;
  border-radius: 20px;
  background: rgba(78,204,163,0.1);
  border: 1px solid rgba(78,204,163,0.25);
  font-size: 0.75rem;
  color: var(--primary);
  cursor: pointer;
  transition: all 0.15s;
}
.chip-link:hover { background: rgba(78,204,163,0.2); }

/* ── Section divider ── */
.section-divider {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.75rem;
}
.section-divider span {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: rgba(255,255,255,0.3);
  white-space: nowrap;
}
.section-divider::before,
.section-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: rgba(255,255,255,0.07);
}
.section-divider::before { display: none; }

/* ── Glass input ── */
.glass-input {
  width: 100%;
  box-sizing: border-box;
  background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 7px;
  padding: 0.45rem 0.65rem;
  color: white;
  font-size: 0.85rem;
}
.glass-input.small { font-size: 0.8rem; padding: 0.35rem 0.55rem; }
.glass-input.mono { font-family: 'JetBrains Mono', Consolas, monospace; }
.glass-input:focus { outline: none; border-color: rgba(78,204,163,0.4); }

/* ── Game split ── */
.game-split {
  display: grid;
  grid-template-columns: minmax(260px, 380px) 1fr;
  gap: 0.75rem;
  height: 100%;
}

.board-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0.5rem;
  min-height: 320px;
}

.game-controls {
  padding: 0.85rem;
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  overflow-y: auto;
}

.stream-row {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-top: 0.25rem;
}
.stream-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}
.stream-label {
  font-size: 0.78rem;
  color: rgba(255,255,255,0.45);
  width: 70px;
  flex-shrink: 0;
}

/* ── JSON Modal ── */
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.6);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
  padding: 1rem;
}

.modal {
  width: min(90vw, 680px);
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  gap: 0;
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.modal-title {
  font-weight: 600;
  color: var(--primary);
  font-size: 0.95rem;
}

.json-box {
  flex: 1;
  overflow: auto;
  padding: 0.85rem 1rem;
  margin: 0;
  color: rgba(255,255,255,0.78);
  font-size: 0.76rem;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'JetBrains Mono', Consolas, monospace;
}

/* ── Responsive ── */
@media (max-width: 860px) {
  .field-row {
    grid-template-columns: 1fr;
  }
  .tournament-split,
  .game-split {
    grid-template-columns: 1fr;
  }
}
</style>
