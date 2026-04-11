<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import ChessBoard from './components/ChessBoard.vue'
import GameControls from './components/GameControls.vue'
import GameStatus from './components/GameStatus.vue'

const gameState = ref({
  fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  status: 'Waiting...',
  activeColor: 'White'
})

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
      <div class="board-section glass-panel">
        <ChessBoard :fen="gameState.fen" @move="(m) => sendCommand(m)" />
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
  height: 100%;
  width: 100%;
}

.app-header {
  flex-shrink: 0;
}

.game-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 1rem;
  width: 100%;
  flex: 1;
  min-height: 0; /* Allow grid to shrink */
  align-items: center;
}

.board-section {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 0.5rem;
  max-height: 100%;
}

.info-section {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  max-height: 100%;
  overflow-y: auto;
  padding-right: 0.5rem;
}

@media (max-width: 1000px) {
  .game-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
    overflow-y: auto;
  }
  
  .info-section {
    order: -1;
  }
}
</style>
