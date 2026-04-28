<script setup>
import { ref } from 'vue'
import ChessBoard from './ChessBoard.vue'

const emit = defineEmits(['start-game', 'goto-puzzles'])

const timePresets = [
  { name: '1|0 Bullet', time: 60000, inc: 0 },
  { name: '3|2 Blitz', time: 180000, inc: 2000 },
  { name: '10|0 Rapid', time: 600000, inc: 0 }
]

const showCustom = ref(false)
const customMin = ref(0)
const customInc = ref(0)

// Starting position for the Game card
const startFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'

// A cool tactic position for the Puzzle card: Queen sacrifice into back-rank mate in 2
const puzzleFen = 'r5k1/5ppp/8/8/8/8/3Q4/4R1K1 w - - 0 1'

const handleStartGame = (p) => {
  emit('start-game', p.time, p.inc)
}

const handleStartCustom = () => {
  const timeMs = customMin.value > 0 ? customMin.value * 60000 : null
  emit('start-game', timeMs, customInc.value * 1000)
}

const handleGoToPuzzles = () => {
  emit('goto-puzzles')
}
</script>

<template>
  <div class="home-view">
    <div class="cards-container">
      
      <!-- Play Game Card -->
      <div class="home-card glass">
        <div class="card-header">
          <h2 class="card-title">Play Game</h2>
          <p class="card-subtitle">Play against friends or the AI</p>
        </div>
        
        <div class="board-preview">
          <ChessBoard 
            :fen="'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1'" 
            :flipped="false" 
            style="--board-square-size: 33px; border-width: 2px; box-shadow: none;"
          />
        </div>

        <div class="actions-area">
          <div class="label-container">
            <label class="section-label">Select Time Control</label>
          </div>
          <div class="time-grid">
            <button 
              v-for="p in timePresets" 
              :key="p.name"
              @click="handleStartGame(p)"
              class="preset-btn primary"
            >
              {{ p.name }}
            </button>
            <button @click="showCustom = !showCustom" class="preset-btn primary">
              Custom
            </button>
          </div>

          <div v-if="showCustom" class="custom-settings glass-card">
            <div class="custom-row">
              <div class="custom-field">
                <label>Time(min)</label>
                <input type="number" v-model="customMin" min="0" max="180" class="glass-input compact">
              </div>
              <div class="custom-field">
                <label>inc(sec)</label>
                <input type="number" v-model="customInc" min="0" max="60" class="glass-input compact">
              </div>
            </div>
            <button @click="handleStartCustom" class="preset-btn primary full-width">
              Start {{ customMin === 0 ? '(Unlimited)' : `(${customMin}m | ${customInc}s)` }}
            </button>
          </div>
        </div>
      </div>

      <!-- Puzzles Card -->
      <div class="home-card glass">
        <div class="card-header">
          <h2 class="card-title">Puzzles</h2>
          <p class="card-subtitle">Improve your tactical skills</p>
        </div>
        
        <div class="board-preview">
          <ChessBoard 
            :fen="puzzleFen" 
            :flipped="false" 
            style="--board-square-size: 33px; border-width: 2px; box-shadow: none;"
          />
        </div>

        <div class="actions-area puzzle-actions">
          <div class="label-container">
            <label class="section-label">Train with 6mio+ positions</label>
          </div>
          <div class="puzzle-btn-container">
            <button @click="handleGoToPuzzles" class="preset-btn primary massive-btn">
              Solve Puzzles
            </button>
          </div>
        </div>
      </div>

    </div>
  </div>
</template>

<style scoped>
.home-view {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  width: 100%;
  padding: 2rem;
}

.cards-container {
  display: flex;
  gap: 2rem;
  max-width: 1000px;
  width: 100%;
  justify-content: center;
  flex-wrap: wrap;
}

.home-card {
  flex: 1;
  min-width: 280px;
  max-width: 360px;
  min-height: 65vh;
  padding: 2rem 1.5rem;
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  text-align: center;
  transition: transform 0.3s cubic-bezier(0.175, 0.885, 0.32, 1.275), box-shadow 0.3s;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.home-card:hover {
  transform: translateY(-8px);
  box-shadow: 0 20px 40px rgba(0,0,0,0.4);
  background: rgba(255, 255, 255, 0.08);
}

.card-header {
  height: 80px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.card-title {
  margin: 0;
  font-size: 2rem;
  font-weight: 800;
  color: var(--primary);
  text-transform: uppercase;
  letter-spacing: 2px;
}

.card-subtitle {
  margin: 0.25rem 0 0;
  font-size: 0.85rem;
  color: rgba(255, 255, 255, 0.6);
}

.board-preview {
  margin: 1rem 0;
  border-radius: 8px;
  overflow: hidden;
  pointer-events: none;
  user-select: none;
  opacity: 0.9;
  transition: opacity 0.3s;
  height: 280px; 
  display: flex;
  align-items: center;
  justify-content: center;
}

.home-card:hover .board-preview {
  opacity: 1;
}

.actions-area {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.label-container {
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.section-label {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 1.5px;
  color: rgba(255, 255, 255, 0.5);
  font-weight: 700;
  margin: 0;
}

.time-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
  height: 92px; /* Fixed height for 2 rows + gap */
}

.preset-btn {
  padding: 10px;
  border-radius: 8px;
  border: 1px solid transparent;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.preset-btn.primary {
  background: rgba(78, 204, 163, 0.15);
  color: var(--primary);
  border-color: rgba(78, 204, 163, 0.3);
}

.preset-btn.primary:hover {
  background: rgba(78, 204, 163, 0.3);
  transform: scale(1.02);
}

.preset-btn.secondary {
  background: rgba(240, 165, 0, 0.15);
  color: var(--accent);
  border-color: rgba(240, 165, 0, 0.3);
}

.preset-btn.secondary:hover {
  background: rgba(240, 165, 0, 0.3);
  transform: scale(1.02);
}

.puzzle-actions {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.puzzle-btn-container {
  height: 92px; /* Exactly matches .time-grid */
  display: flex;
  align-items: center;
  justify-content: center;
}

.massive-btn {
  padding: 12px;
  font-size: 1.1rem;
  width: 100%;
}

.custom-settings {
  margin-top: 0.5rem;
  padding: 1rem;
  border-radius: 12px;
  background: rgba(255,255,255,0.03);
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.custom-row {
  display: flex;
  gap: 0.5rem;
  width: 100%;
}

.custom-field {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0; /* Prevents overflow */
}

.custom-field label {
  font-size: 0.65rem;
  text-transform: uppercase;
  color: rgba(255,255,255,0.5);
}

.full-width {
  width: 100%;
}

.glass-input {
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  color: white;
  padding: 8px;
  border-radius: 6px;
  outline: none;
  text-align: center;
  width: 100%;
  box-sizing: border-box;
}

.glass-input.compact {
  padding: 6px 4px;
  font-size: 0.85rem;
}
</style>
