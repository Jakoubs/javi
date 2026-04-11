<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import ChessBoard from './components/ChessBoard.vue'
import GameControls from './components/GameControls.vue'
import GameStatus from './components/GameStatus.vue'

const gameState = ref({
  fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  status: 'Waiting...',
  activeColor: 'White',
  topPlayer: null,
  bottomPlayer: null,
  aiWhite: false,
  aiBlack: false
})

const formatTime = (ms) => {
  if (!ms) return "00:00"
  const totalSec = Math.max(0, ms) / 1000
  const mn = Math.floor(totalSec / 60)
  const sc = Math.floor(totalSec % 60)
  return `${mn.toString().padStart(2, '0')}:${sc.toString().padStart(2, '0')}`
}

const fetchState = async () => {
  try {
    const response = await fetch('http://localhost:8080/api/state')
    if (response.ok) {
      gameState.value = await response.json()
    }
  } catch (error) {
    console.error('Failed to fetch game state:', error)
  }
}

const sendCommand = async (command) => {
  try {
    const response = await fetch('http://localhost:8080/api/command', {
      method: 'POST',
      body: JSON.stringify({ command })
    })
    if (response.ok) {
      await fetchState()
    }
  } catch (error) {
    console.error('Failed to send command:', error)
  }
}

let pollInterval
onMounted(() => {
  fetchState()
  pollInterval = setInterval(fetchState, 1000)
})

onUnmounted(() => {
  clearInterval(pollInterval)
})
</script>

<template>
  <div class="app-container">
    <header class="app-header">
      <h1>JAVI CHESS</h1>
      <p class="subtitle">PREMIUM STRATEGY EXPERIENCE</p>
    </header>

    <main class="game-layout">
      <div class="board-column">
        <div v-if="gameState.topPlayer" class="player-info top">
          <div class="info-row">
            <span class="player-color">{{ gameState.topPlayer.color }}</span>
            <div class="captured-symbols">
              <span v-for="(s, i) in gameState.topPlayer.capturedSymbols" :key="i" class="piece">{{ s }}</span>
              <span v-if="gameState.topPlayer.advantage > 0" class="adv-badge">+{{ gameState.topPlayer.advantage }}</span>
            </div>
            <span class="clock">{{ formatTime(gameState.topPlayer.clockMillis) }}</span>
          </div>
        </div>

        <div class="board-section glass-panel">
          <ChessBoard :fen="gameState.fen" @move="(m) => sendCommand(m)" />
        </div>

        <div v-if="gameState.bottomPlayer" class="player-info bottom">
          <div class="info-row">
            <span class="player-color">{{ gameState.bottomPlayer.color }}</span>
            <div class="captured-symbols">
              <span v-for="(s, i) in gameState.bottomPlayer.capturedSymbols" :key="i" class="piece">{{ s }}</span>
              <span v-if="gameState.bottomPlayer.advantage > 0" class="adv-badge">+{{ gameState.bottomPlayer.advantage }}</span>
            </div>
            <span class="clock">{{ formatTime(gameState.bottomPlayer.clockMillis) }}</span>
          </div>
        </div>
      </div>

      <aside class="info-section">
        <GameStatus :state="gameState" />
        <GameControls :state="gameState" @command="sendCommand" />
      </aside>
    </main>
  </div>
</template>

<style scoped>
.app-container {
  display: flex;
  flex-direction: column;
  gap: 2rem;
  align-items: center;
  padding: 2rem;
  min-height: 100vh;
  background: radial-gradient(circle at center, #1a202c 0%, #0f172a 100%);
}

.app-header {
  text-align: center;
  margin-bottom: 1rem;
}

.game-layout {
  display: grid;
  grid-template-columns: min-content 350px;
  gap: 3rem;
  max-width: 1400px;
  width: 100%;
  align-items: center;
}

.board-column {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.player-info {
  background: rgba(0, 0, 0, 0.25);
  padding: 0.75rem 1.25rem;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(8px);
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 2rem;
}

.player-color {
  font-weight: 800;
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.15rem;
  color: #718096;
  min-width: 60px;
}

.captured-symbols {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
}

.piece {
  font-size: 1.5rem;
  line-height: 1;
  filter: drop-shadow(0 2px 4px rgba(0,0,0,0.5));
}

.top .piece {
  color: #fff;
  -webkit-text-stroke: 0.5px #000;
}

.bottom .piece {
  color: #fff;
  -webkit-text-stroke: 0.5px #000;
}

.clock {
  font-family: 'Fira Code', monospace;
  font-weight: 700;
  font-size: 1.3rem;
  color: #fff;
  background: rgba(0, 0, 0, 0.4);
  padding: 4px 12px;
  border-radius: 6px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: inset 0 2px 4px rgba(0,0,0,0.3);
}

.adv-badge {
  font-size: 0.8rem;
  font-weight: 900;
  background: rgba(72, 187, 120, 0.15);
  color: #48bb78;
  padding: 3px 8px;
  border-radius: 6px;
  margin-left: 0.5rem;
  border: 1px solid rgba(72, 187, 120, 0.2);
}

.board-section {
  padding: 1rem;
  border-radius: 12px;
}

.info-section {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  max-height: 100%;
}

@media (max-width: 1200px) {
  .game-layout {
    grid-template-columns: 1fr;
    justify-content: center;
  }
  
  .board-column {
    align-items: center;
  }

  .player-info {
    width: 640px;
  }
}
</style>
