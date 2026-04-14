<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
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
  trainingProgress: null
,
  whiteLiveMillis: 0,
  blackLiveMillis: 0})

const fetchState = async () => {
  try {
    const response = await fetch('http://localhost:8080/api/state')
    if (response.ok) {
      state.value = await response.json()
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
      <h1>JAVI CHESS</h1>
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
</style>
