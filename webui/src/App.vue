<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import ChessBoard from './components/ChessBoard.vue'
import GameControls from './components/GameControls.vue'
import GameStatus from './components/GameStatus.vue'
import ImportExport from './components/ImportExport.vue'
import TimeSettings from './components/TimeSettings.vue'
import MoveHistory from './components/MoveHistory.vue'

// ── Server URL (persisted in localStorage) ────────────────────────────────
const DEFAULT_SERVER = 'http://localhost:8080'
const serverUrl = ref(localStorage.getItem('chessServerUrl') || DEFAULT_SERVER)
const serverInput = ref(serverUrl.value)
const serverConnected = ref(true)

// ── Session ID (persisted in localStorage) ────────────────────────────────
const generateId = () => Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15)
const sessionId = ref(localStorage.getItem('chessSessionId') || generateId())
if (!localStorage.getItem('chessSessionId')) {
  localStorage.setItem('chessSessionId', sessionId.value)
}

// ── Player Role (White / Black / Spectator) ──────────────────────────────
const clientRole = ref(localStorage.getItem('chessClientRole') || 'spectator')

const setRole = (role) => {
  clientRole.value = role
  localStorage.setItem('chessClientRole', role)
}

const applyServer = () => {
  const url = serverInput.value.trim().replace(/\/$/, '')
  if (!url) return
  serverUrl.value = url
  serverInput.value = url
  localStorage.setItem('chessServerUrl', url)
  fetchState()
}
const resetServer = () => {
  serverInput.value = DEFAULT_SERVER
  applyServer()
}

const state = ref({
  fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  pgn: '',
  status: 'Playing',
  activeColor: 'White',
  highlights: [],
  selectedPos: null,
  lastMove: null,
  aiWhite: false,
  aiBlack: false,
  flipped: false,
  viewIndex: 0,
  historyFen: [],
  historyMoves: [],
  displayFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  clock: null,
  capturedWhite: [],
  capturedBlack: [],
  message: '',
  training: false,
  trainingProgress: null,
  whiteLiveMillis: 0,
  blackLiveMillis: 0,
  activePgnParser: 'regex'
})

// Watch for messages and clear them automatically after a timeout
watch(() => state.value.message, (newMsg) => {
  if (newMsg) {
    setTimeout(() => {
      if (state.value.message === newMsg) {
        state.value.message = ''
      }
    }, 4000)
  }
})

const showGameOver = ref(false)
const processedStatus = ref(null)

const isGameOver = computed(() => {
  const s = state.value.status
  return s !== 'Playing' && !s.startsWith('Check(') && s !== ''
})

const gameOverInfo = computed(() => {
  const s = state.value.status
  if (s.startsWith('Checkmate')) {
    const winner = s.includes('White') ? 'Black' : 'White'
    return { title: 'Checkmate!', result: `${winner} Wins`, reason: s }
  }
  if (s.startsWith('Resigned')) {
    const winner = s.includes('White') ? 'Black' : 'White'
    return { title: 'Resignation', result: `${winner} Wins`, reason: s }
  }
  if (s.startsWith('Timeout')) {
    const winner = s.includes('White') ? 'Black' : 'White'
    return { title: 'Time Out!', result: `${winner} Wins`, reason: s }
  }
  if (s === 'Stalemate') return { title: 'Stalemate', result: 'Draw', reason: 'No legal moves left' }
  if (s.startsWith('Draw')) {
    // Try to extract reason from Draw(reason)
    const reason = s.match(/Draw\((.*)\)/)?.[1] || s
    return { title: 'Draw', result: 'Game is Drawn', reason: reason }
  }
  return { title: 'Game Over', result: '', reason: s }
})



const handleNewGameModal = () => {
  sendCommand('new')
  showGameOver.value = false
}

const handleQuitModal = () => {
  sendCommand('quit')
  showGameOver.value = false
}

const fetchState = async () => {
  try {
    const response = await fetch(`${serverUrl.value}/api/state?sessionId=${sessionId.value}&t=${Date.now()}`)
    if (response.ok) {
      serverConnected.value = true
      const data = await response.json()
      state.value = data
      
      // Proactive game-over check
      if (isGameOver.value && processedStatus.value !== data.status) {
        showGameOver.value = true
        processedStatus.value = data.status
      } else if (!isGameOver.value) {
        processedStatus.value = null
      }
    }
  } catch (e) {
    serverConnected.value = false
    console.error('Failed to fetch state', e)
  }
}

const sendCommand = async (cmd) => {
  // Move enforcement: only allow moves if it's our turn
  const isBoardInteraction = /^[a-h][1-8]$/.test(cmd) || /^[a-h][1-8][a-h][1-8]/.test(cmd)
  const isGameAction = ['undo', 'resign'].includes(cmd)
  
  if (isBoardInteraction || isGameAction) {
    if (clientRole.value === 'spectator') {
      state.value.message = "You are spectating. Please select a role to play."
      return
    }
    const myTurn = (clientRole.value === 'white' && state.value.activeColor === 'White') ||
                   (clientRole.value === 'black' && state.value.activeColor === 'Black')
    if (!myTurn) {
      state.value.message = `It's not your turn! Waiting for ${state.value.activeColor}.`
      return
    }
  }

  try {
    await fetch(`${serverUrl.value}/api/command?sessionId=${sessionId.value}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ command: cmd })
    })
    fetchState()
  } catch (e) {
    console.error('Failed to send command', e)
  }
}

const handleLoadFen = (fen) => sendCommand(`load fen ${fen}`)
const handleLoadPgn = (pgn) => sendCommand(`load pgn ${pgn}`)
const handleStartWithTime = (time, inc) => {
  if (time === null) sendCommand('start none')
  else sendCommand(`start ${time} ${inc}`)
}

const handleSwitchParser = (event) => {
  const variant = event.target.value
  sendCommand(`parser pgn ${variant}`)
}


let pollInterval
let tickerInterval

onMounted(() => {
  fetchState()
  pollInterval = setInterval(fetchState, 500)
  
  tickerInterval = setInterval(() => {
    if (state.value.status === 'Playing' && state.value.clock && state.value.clock.isActive && state.value.clock.lastTickSysTime) {
      if (state.value.activeColor === 'White') {
        state.value.whiteLiveMillis = Math.max(0, state.value.whiteLiveMillis - 100)
      } else {
        state.value.blackLiveMillis = Math.max(0, state.value.blackLiveMillis - 100)
      }
    }
  }, 100)
})

onUnmounted(() => {
  clearInterval(pollInterval)
  clearInterval(tickerInterval)
})


const topPlayer = computed(() => {
  const isFlipped = state.value.flipped
  const mat = state.value.materialInfo
  return {
    name: isFlipped ? 'White Player' : 'Black Player',
    color: isFlipped ? 'White' : 'Black',
    captured: mat ? (isFlipped ? mat.blackCapturedSymbols : mat.whiteCapturedSymbols) : [],
    advantage: mat ? (isFlipped ? mat.whiteAdvantage : mat.blackAdvantage) : 0,
    clock: state.value.clock ? (isFlipped ? state.value.whiteLiveMillis : state.value.blackLiveMillis) : null
  }
})

const bottomPlayer = computed(() => {
  const isFlipped = state.value.flipped
  const mat = state.value.materialInfo
  return {
    name: isFlipped ? 'Black Player' : 'White Player',
    color: isFlipped ? 'Black' : 'White',
    captured: mat ? (isFlipped ? mat.whiteCapturedSymbols : mat.blackCapturedSymbols) : [],
    advantage: mat ? (isFlipped ? mat.blackAdvantage : mat.whiteAdvantage) : 0,
    clock: state.value.clock ? (isFlipped ? state.value.blackLiveMillis : state.value.whiteLiveMillis) : null
  }
})

const formatTime = (ms) => {
  if (ms === null || ms === undefined) return ''
  const totalSec = Math.max(0, Math.floor(ms / 1000))
  const m = Math.floor(totalSec / 60)
  const s = totalSec % 60
  return `${m}:${s.toString().padStart(2, '0')}`
}

const isViewingHistory = computed(() => {
  const h = state.value.historyFen
  const i = state.value.viewIndex
  return h && h.length > 0 && i < h.length - 1
})

const effectiveFlipped = computed(() => {
  if (clientRole.value === 'white') return false
  if (clientRole.value === 'black') return true
  return state.value.flipped
})
</script>

<template>
  <div class="app-container">
    <header class="glass">
      <div class="header-left">
        <h1>JAVI CHESS</h1>
        <div class="parser-switcher">
          <label for="parser-select">PGN Parser:</label>
          <select id="parser-select" :value="state.activePgnParser" @change="handleSwitchParser" class="glass-select">
            <option value="regex">Regex</option>
            <option value="fast">Fastparse</option>
            <option value="combinator">Combinator</option>
          </select>
        </div>
      </div>

      <!-- Server URL switcher -->
      <div class="server-switcher">
        <span class="server-dot" :class="{ connected: serverConnected, disconnected: !serverConnected }" :title="serverConnected ? 'Connected' : 'Disconnected'"></span>
        <input
          id="server-url-input"
          v-model="serverInput"
          class="server-input glass-input"
          placeholder="http://host:8080"
          @keyup.enter="applyServer"
          @blur="applyServer"
          spellcheck="false"
        />
        <button class="server-reset-btn" @click="resetServer" title="Reset to localhost">↺</button>
      </div>

      <!-- Player Role Selector -->
      <div class="role-selector glass-pill">
        <button 
          v-for="role in ['white', 'black', 'spectator']" 
          :key="role"
          :class="['role-btn', role, { active: clientRole === role }]"
          @click="setRole(role)"
        >
          {{ role === 'spectator' ? '👁' : (role === 'white' ? '♔' : '♚') }}
          <span class="role-label">{{ role.charAt(0).toUpperCase() + role.slice(1) }}</span>
        </button>
      </div>

      <Transition name="slide-down">
        <div v-if="state.message" class="status-msg shadow-lg">{{ state.message }}</div>
      </Transition>
    </header>

    <main>
      <div class="left-panel">
        <GameStatus :state="state" />
        <MoveHistory 
          :moves="state.historyMoves || []" 
          :viewIndex="state.viewIndex" 
          @jump="(i) => sendCommand(`jump ${i}`)" 
          @command="sendCommand"
        />
      </div>

      <div class="board-container">
        <!-- Top Player Info -->
        <div class="player-info top">
          <div class="player-meta">
            <span class="p-name">{{ topPlayer.name }}</span>
            <div class="captured-list">
              <span v-for="(s, i) in topPlayer.captured" :key="i" class="piece-icon">{{ s }}</span>
              <span v-if="topPlayer.advantage > 0" class="advantage">+{{ topPlayer.advantage }}</span>
            </div>
          </div>
          <div :class="['clock glass', { active: state.activeColor === topPlayer.color }]">
            {{ formatTime(topPlayer.clock) }}
          </div>
        </div>

        <ChessBoard 
          :fen="state.displayFen" 
          :flipped="effectiveFlipped"
          :highlights="isViewingHistory ? [] : state.highlights"
          :selectedPos="isViewingHistory ? null : state.selectedPos"
          @square-click="(pos) => sendCommand(pos)"
        />

        <!-- Bottom Player Info -->
        <div class="player-info bot">
          <div class="player-meta">
            <span class="p-name">{{ bottomPlayer.name }}</span>
            <div class="captured-list">
              <span v-for="(s, i) in bottomPlayer.captured" :key="i" class="piece-icon">{{ s }}</span>
              <span v-if="bottomPlayer.advantage > 0" class="advantage">+{{ bottomPlayer.advantage }}</span>
            </div>
          </div>
          <div :class="['clock glass', { active: state.activeColor === bottomPlayer.color }]">
            {{ formatTime(bottomPlayer.clock) }}
          </div>
        </div>
      </div>

      <div class="right-panel">
        <TimeSettings @startWithTime="handleStartWithTime" />
        <ImportExport 
          :fen="state.fen" 
          :pgn="state.pgn" 
          @loadFen="handleLoadFen" 
          @loadPgn="handleLoadPgn"
        />
        <GameControls :state="state" @command="sendCommand" />
      </div>
    </main>

    <!-- Game Over Modal -->
    <div v-if="showGameOver" class="modal-overlay">
      <div class="modal-content glass">
        <button class="close-x" @click="showGameOver = false">&times;</button>
        <div class="modal-header">
          <h2>{{ gameOverInfo.title }}</h2>
        </div>
        <div class="modal-body">
          <div class="result-text">{{ gameOverInfo.result }}</div>
          <div class="reason-text">{{ gameOverInfo.reason }}</div>
        </div>
        <div class="modal-footer">
          <button class="btn primary" @click="handleNewGameModal">New Game</button>
          <button class="btn" @click="handleQuitModal">Quit</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
:root {
  --bg-color: #1a1a2e;
  --glass-bg: rgba(255, 255, 255, 0.05);
  --glass-border: rgba(255, 255, 255, 0.1);
  --primary: #4ecca3;
  --accent: #f0a500;
}

body {
  margin: 0;
  background: var(--bg-color);
  color: white;
  font-family: 'Inter', sans-serif;
  overflow: hidden;
}

.app-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

.glass {
  background: var(--glass-bg);
  backdrop-filter: blur(10px);
  border: 1px solid var(--glass-border);
}

header {
  padding: 1rem 2rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
  position: relative; /* For absolute status-msg */
  z-index: 100;
}

header h1 {
  margin: 0;
  font-size: 1.5rem;
  letter-spacing: 2px;
  color: var(--primary);
}

.status-msg {
  position: absolute;
  top: calc(100% - 10px);
  left: 50%;
  transform: translateX(-50%);
  background: rgba(240, 165, 0, 0.95);
  backdrop-filter: blur(5px);
  color: #fff;
  padding: 8px 20px;
  border-radius: 30px;
  font-size: 0.9rem;
  font-weight: 600;
  white-space: nowrap;
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.3);
  border: 1px solid rgba(240, 165, 0, 0.3);
  z-index: 1001;
}

/* ── Transitions ──────────────────────────────────────────────────────── */
.slide-down-enter-active,
.slide-down-leave-active {
  transition: all 0.3s cubic-bezier(0.18, 0.89, 0.32, 1.28);
}

.slide-down-enter-from {
  opacity: 0;
  transform: translateX(-50%) translateY(-20px) scale(0.9);
}

.slide-down-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-10px) scale(0.95);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 2rem;
}

.parser-switcher {
  display: flex;
  align-items: center;
  gap: 0.8rem;
  font-size: 0.85rem;
  opacity: 0.8;
}

.parser-switcher label {
  font-weight: 600;
  color: var(--primary);
  text-transform: uppercase;
  letter-spacing: 1px;
}

.glass-select {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid var(--glass-border);
  color: white;
  padding: 4px 8px;
  border-radius: 6px;
  outline: none;
  cursor: pointer;
  transition: all 0.2s;
}

.glass-select:hover {
  background: rgba(255, 255, 255, 0.15);
  border-color: var(--primary);
}

.glass-select option {
  background: #1a1a2e;
  color: white;
}

main {
  flex: 1;
  display: flex;
  padding: 1.5rem;
  gap: 1.5rem;
  overflow: hidden;
}

.left-panel, .right-panel {
  width: 320px;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  overflow-y: auto;
}

.board-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1rem;
}

.player-info {
  width: 500px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.player-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.p-name {
  font-weight: 700;
  font-size: 1rem;
}

.captured-list {
  display: flex;
  gap: 4px;
  opacity: 0.7;
}

.advantage {
  margin-left: 8px;
  opacity: 0.9;
  font-weight: normal;
}

.clock {
  padding: 8px 16px;
  border-radius: 8px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 1.5rem;
  font-weight: 700;
}

.clock.active {
  background: rgba(78, 204, 163, 0.2);
  border-color: var(--primary);
  color: var(--primary);
}

/* Modal Styles */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.modal-content {
  position: relative;
  width: 400px;
  padding: 2rem;
  border-radius: 16px;
  text-align: center;
  box-shadow: 0 20px 40px rgba(0,0,0,0.4);
  animation: modal-in 0.3s ease-out;
}

@keyframes modal-in {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

.close-x {
  position: absolute;
  top: 1rem;
  right: 1.2rem;
  background: none;
  border: none;
  color: white;
  font-size: 1.8rem;
  cursor: pointer;
  opacity: 0.5;
  transition: opacity 0.2s;
  line-height: 1;
}

.close-x:hover {
  opacity: 1;
}

.modal-header h2 {
  margin: 0 0 1rem 0;
  color: var(--primary);
  letter-spacing: 1px;
}

.modal-body {
  margin-bottom: 2rem;
}

.result-text {
  font-size: 1.4rem;
  font-weight: 700;
  margin-bottom: 0.5rem;
}

.reason-text {
  font-size: 0.9rem;
  opacity: 0.7;
  font-style: italic;
}

.modal-footer {
  display: flex;
  gap: 1rem;
  justify-content: center;
}

.btn {
  padding: 10px 24px;
  border-radius: 8px;
  border: 1px solid var(--glass-border);
  background: var(--glass-bg);
  color: white;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn:hover {
  background: rgba(255, 255, 255, 0.1);
  transform: translateY(-2px);
}

.btn.primary {
  background: var(--primary);
  border-color: var(--primary);
  color: #1a1a2e;
}

.btn.primary:hover {
  background: #3eb88f;
  box-shadow: 0 4px 12px rgba(78, 204, 163, 0.3);
}

/* ── Server Switcher ──────────────────────────────────────────────────── */
.server-switcher {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.server-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  transition: background 0.3s;
}
.server-dot.connected    { background: #4ecca3; box-shadow: 0 0 6px #4ecca3; }
.server-dot.disconnected { background: #e74c3c; box-shadow: 0 0 6px #e74c3c; animation: pulse-red 1s infinite; }

@keyframes pulse-red {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0.4; }
}

.glass-input {
  background: rgba(255,255,255,0.07);
  border: 1px solid var(--glass-border);
  border-radius: 8px;
  color: white;
  padding: 5px 10px;
  font-size: 0.82rem;
  width: 200px;
  outline: none;
  font-family: 'JetBrains Mono', monospace;
  transition: border-color 0.2s, background 0.2s;
}
.glass-input:focus {
  border-color: var(--primary);
  background: rgba(78, 204, 163, 0.08);
}

.server-reset-btn {
  background: none;
  border: 1px solid var(--glass-border);
  color: rgba(255,255,255,0.5);
  border-radius: 6px;
  width: 28px;
  height: 28px;
  cursor: pointer;
  font-size: 1rem;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.server-reset-btn:hover {
  color: var(--primary);
  border-color: var(--primary);
  transform: rotate(-30deg);
}

/* ── Role Selector ────────────────────────────────────────────────────── */
.role-selector {
  display: flex;
  padding: 4px;
  gap: 4px;
}

.glass-pill {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--glass-border);
  border-radius: 30px;
}

.role-btn {
  background: none;
  border: none;
  color: rgba(255, 255, 255, 0.4);
  padding: 6px 14px;
  border-radius: 20px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.8rem;
  font-weight: 600;
  transition: all 0.3s;
}

.role-btn .role-label {
  display: none;
}

.role-btn:hover {
  color: white;
  background: rgba(255, 255, 255, 0.05);
}

.role-btn.active {
  color: #1a1a2e;
  background: white;
  box-shadow: 0 4px 12px rgba(0,0,0,0.2);
}

.role-btn.active.white { background: #fff; color: #1a1a2e; }
.role-btn.active.black { background: #333; color: #fff; border: 1px solid rgba(255,255,255,0.2); }
.role-btn.active.spectator { background: var(--accent); color: #1a1a2e; }

@media (min-width: 1200px) {
  .role-btn .role-label {
    display: inline;
  }
}
</style>
