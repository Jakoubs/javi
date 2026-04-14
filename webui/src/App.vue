<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import ChessBoard from './components/ChessBoard.vue'
import GameControls from './components/GameControls.vue'
import GameStatus from './components/GameStatus.vue'
import ImportExport from './components/ImportExport.vue'
import TimeSettings from './components/TimeSettings.vue'
import MoveHistory from './components/MoveHistory.vue'

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
  if (s.startsWith('Timeout')) {
    const winner = s.includes('White') ? 'Black' : 'White'
    return { title: 'Time Out!', result: `${winner} Wins`, reason: s }
  }
  if (s === 'Stalemate') return { title: 'Draw', result: 'Stalemate', reason: 'No legal moves left' }
  if (s.startsWith('Draw')) return { title: 'Draw', result: 'Game is Drawn', reason: s }
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
    const response = await fetch(`http://localhost:8080/api/state?t=${Date.now()}`)
    if (response.ok) {
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
    console.error('Failed to fetch state', e)
  }
}

const sendCommand = async (cmd) => {
  try {
    await fetch('http://localhost:8080/api/command', {
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
      <div v-if="state.message" class="status-msg">{{ state.message }}</div>
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
          :flipped="state.flipped"
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
}

header h1 {
  margin: 0;
  font-size: 1.5rem;
  letter-spacing: 2px;
  color: var(--primary);
}

.status-msg {
  background: rgba(240, 165, 0, 0.2);
  color: #f0a500;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 0.9rem;
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
</style>
