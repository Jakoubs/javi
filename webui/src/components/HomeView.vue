<script setup>
import { ref, watch, onUnmounted, onMounted } from 'vue'
import ChessBoard from './ChessBoard.vue'

const props = defineProps({
  isQueueing: Boolean,
  serverUrl: { type: String, default: 'http://localhost:8080' }
})

const emit = defineEmits(['start-game', 'join-queue', 'cancel-queue', 'goto-puzzles', 'spectate-party'])

const queueSeconds = ref(0)
let queueTimer = null

watch(() => props.isQueueing, (val) => {
  if (val) {
    queueSeconds.value = 0
    queueTimer = setInterval(() => { queueSeconds.value++ }, 1000)
  } else {
    clearInterval(queueTimer)
    queueTimer = null
  }
})

const activeGames = ref([])
const fetchActiveGames = async () => {
  try {
    const res = await fetch(`${props.serverUrl}/api/party/active`)
    if (res.ok) activeGames.value = await res.json()
  } catch (e) {
    console.error('Failed to fetch active games', e)
  }
}

let activeGamesInterval = null

onMounted(() => {
  console.log('HomeView mounted, fetching active games...')
  fetchActiveGames()
  activeGamesInterval = setInterval(fetchActiveGames, 5000)
})

onUnmounted(() => {
  clearInterval(queueTimer)
  clearInterval(activeGamesInterval)
})

const formatQueueTime = (s) => {
  const m = Math.floor(s / 60)
  const sec = s % 60
  return m > 0 ? `${m}:${sec.toString().padStart(2, '0')}` : `${sec}s`
}

const timePresets = [
  { name: '1|0 Bullet', time: 60000, inc: 0 },
  { name: '3|2 Blitz', time: 180000, inc: 2000 },
  { name: '10|0 Rapid', time: 600000, inc: 0 }
]

const showCustom = ref(false)
const customMin = ref(0)
const customInc = ref(0)

// A cool tactic position for the Puzzle card: Queen sacrifice into back-rank mate in 2
const puzzleFen = 'r5k1/5ppp/8/8/8/8/3Q4/4R1K1 w - - 0 1'

const playMode = ref('online') // 'online' or 'local'

// Local sub-mode: '2player' | 'ai-white' | 'ai-black' | null (selecting)
const localSubMode = ref(null)

const setPlayMode = (mode) => {
  playMode.value = mode
  localSubMode.value = null
  showCustom.value = false
}

const handleStartGame = (p) => {
  if (playMode.value === 'online') {
    emit('join-queue', p.time, p.inc)
  } else {
    // localSubMode must be set
    if (!localSubMode.value) return
    emit('start-game', p.time, p.inc, localSubMode.value)
  }
}

const handleStartCustom = () => {
  const timeMs = customMin.value > 0 ? customMin.value * 60000 : null
  if (playMode.value === 'online') {
    emit('join-queue', timeMs, customInc.value * 1000)
  } else {
    if (!localSubMode.value) return
    emit('start-game', timeMs, customInc.value * 1000, localSubMode.value)
  }
}

const handleGoToPuzzles = () => {
  emit('goto-puzzles')
}

const spectateCodeInput = ref('')

const handleJoinSpectate = (code) => {
  const c = code || spectateCodeInput.value.trim().toUpperCase()
  if (c) {
    emit('spectate-party', c)
  }
}
</script>

<template>
  <div class="home-view">
    <div class="cards-container">
      
      <!-- Watch Live Card -->
      <div class="home-card glass">
        <div class="card-header">
          <h2 class="card-title">Watch Live</h2>
          <p class="card-subtitle">Spectate ongoing matches</p>
        </div>
        
        <div class="live-games-list">
          <div v-if="activeGames.length === 0" class="no-games">
            <div class="no-games-icon">📺</div>
            <p>No active games right now.</p>
          </div>
          <div v-else class="games-scroll">
            <div v-for="game in activeGames" :key="game.partyCode" class="live-game-item" @click="handleJoinSpectate(game.partyCode)">
              <div class="live-players">
                <span class="live-player white">♔ {{ game.whiteUser }}</span>
                <span class="vs">vs</span>
                <span class="live-player black">♚ {{ game.blackUser }}</span>
              </div>
              <button class="spectate-btn">👁 Watch</button>
            </div>
          </div>
        </div>

        <div class="actions-area">
          <div class="label-container">
            <label class="section-label">Join by Party Code</label>
          </div>
          <div class="join-code-row">
            <input 
              v-model="spectateCodeInput" 
              placeholder="Code (e.g. ABCD)" 
              class="glass-input spectate-input"
              @keyup.enter="handleJoinSpectate()"
              spellcheck="false"
            >
            <button @click="handleJoinSpectate()" class="preset-btn primary spectate-join-btn">
              Join
            </button>
          </div>
        </div>
      </div>

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
          <!-- Mode Toggle -->
          <div class="mode-toggle" style="display: flex; gap: 0.5rem; margin-bottom: 1rem; background: rgba(255,255,255,0.05); padding: 5px; border-radius: 8px;">
            <button 
              @click="setPlayMode('online')" 
              :class="['preset-btn', { primary: playMode === 'online' }]" 
              style="flex: 1; padding: 6px 0; font-size: 0.85rem;"
            >
              Play Online
            </button>
            <button 
              @click="setPlayMode('local')" 
              :class="['preset-btn', { primary: playMode === 'local' }]" 
              style="flex: 1; padding: 6px 0; font-size: 0.85rem;"
            >
              Play Locally
            </button>
          </div>

          <!-- Queue Animation Screen -->
          <div v-if="props.isQueueing" class="queue-screen">
            <div class="queue-ring">
              <div class="queue-ring-inner">
                <span class="queue-piece">♟</span>
              </div>
            </div>
            <p class="queue-label">Suche nach Gegner...</p>
            <div class="queue-timer">{{ formatQueueTime(queueSeconds) }}</div>
            <button @click="emit('cancel-queue')" class="cancel-queue-btn">Abbrechen</button>
          </div>

          <!-- Local Sub-Mode Selection -->
          <div v-if="playMode === 'local' && !props.isQueueing" class="local-submodes">
            <div class="local-submode-toggle">
              <button
                @click="localSubMode = '2player'"
                :class="['submode-btn', { active: localSubMode === '2player' }]"
              >
                <span class="submode-icon">👥</span>
                2 Spieler
              </button>
              <button
                @click="localSubMode = localSubMode === 'ai-white' || localSubMode === 'ai-black' ? localSubMode : 'ai-white'"
                :class="['submode-btn', { active: localSubMode === 'ai-white' || localSubMode === 'ai-black' }]"
              >
                <span class="submode-icon">🤖</span>
                Gegen KI
              </button>
            </div>

            <!-- AI color picker -->
            <div v-if="localSubMode === 'ai-white' || localSubMode === 'ai-black'" class="ai-color-picker">
              <span class="ai-color-label">Du spielst als:</span>
              <div class="ai-color-btns">
                <button
                  @click="localSubMode = 'ai-black'"
                  :class="['color-pick-btn', { selected: localSubMode === 'ai-black' }]"
                >♔ Weiß</button>
                <button
                  @click="localSubMode = 'ai-white'"
                  :class="['color-pick-btn', { selected: localSubMode === 'ai-white' }]"
                >♚ Schwarz</button>
              </div>
            </div>
          </div>

          <!-- Time Control (only shown when sub-mode selected or online) -->
          <template v-if="playMode === 'online' || (playMode === 'local' && localSubMode)">
            <div class="label-container" v-if="!props.isQueueing">
              <label class="section-label">Select Time Control</label>
            </div>
            <div class="time-grid" v-if="!props.isQueueing">
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
          </template>
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
  min-height: 100%;
  width: 100%;
  padding: 2rem;
}

.cards-container {
  display: flex;
  gap: 2rem;
  max-width: 1200px;
  width: 100%;
  justify-content: center;
  flex-wrap: wrap;
}

.home-card {
  flex: 1;
  min-width: 280px;
  max-width: 380px;
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

/* Queue Screen */
.queue-screen {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0;
}

.queue-ring {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  border: 3px solid rgba(78, 204, 163, 0.2);
  border-top-color: #4ecca3;
  animation: spin 1.2s linear infinite;
  display: flex;
  align-items: center;
  justify-content: center;
}

.queue-ring-inner {
  animation: spin 1.2s linear infinite reverse;
}

.queue-piece {
  font-size: 1.8rem;
  line-height: 1;
  filter: drop-shadow(0 0 8px rgba(78,204,163,0.6));
}

.queue-label {
  margin: 0;
  font-size: 0.95rem;
  color: #4ecca3;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.queue-timer {
  font-size: 1.5rem;
  font-weight: 700;
  color: rgba(255,255,255,0.9);
  font-variant-numeric: tabular-nums;
  letter-spacing: 1px;
  min-width: 60px;
  text-align: center;
}

.cancel-queue-btn {
  padding: 8px 24px;
  border-radius: 8px;
  border: 1px solid rgba(255,107,107,0.4);
  background: rgba(255,107,107,0.12);
  color: #ff6b6b;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  margin-top: 4px;
}

.cancel-queue-btn:hover {
  background: rgba(255,107,107,0.25);
  transform: scale(1.03);
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* Local sub-mode UI */
.local-submodes {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin-bottom: 0.25rem;
}

.local-submode-toggle {
  display: flex;
  gap: 0.5rem;
  background: rgba(255,255,255,0.04);
  padding: 4px;
  border-radius: 10px;
  border: 1px solid rgba(255,255,255,0.08);
}

.submode-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 4px;
  border-radius: 7px;
  border: none;
  background: transparent;
  color: rgba(255,255,255,0.5);
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.submode-btn:hover {
  color: rgba(255,255,255,0.85);
  background: rgba(255,255,255,0.06);
}

.submode-btn.active {
  background: rgba(78, 204, 163, 0.18);
  color: #4ecca3;
  border: 1px solid rgba(78, 204, 163, 0.35);
}

.submode-icon {
  font-size: 1.1rem;
}

.ai-color-picker {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  align-items: center;
}

.ai-color-label {
  font-size: 0.72rem;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: rgba(255,255,255,0.45);
  font-weight: 700;
}

.ai-color-btns {
  display: flex;
  gap: 0.5rem;
  width: 100%;
}

.color-pick-btn {
  flex: 1;
  padding: 7px 4px;
  border-radius: 8px;
  border: 1px solid rgba(255,255,255,0.1);
  background: rgba(255,255,255,0.04);
  color: rgba(255,255,255,0.6);
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.color-pick-btn:hover {
  border-color: rgba(78, 204, 163, 0.3);
  color: #4ecca3;
  background: rgba(78, 204, 163, 0.08);
}

.color-pick-btn.selected {
  background: rgba(240, 165, 0, 0.15);
  color: #f0a500;
  border-color: rgba(240, 165, 0, 0.4);
  box-shadow: 0 0 10px rgba(240, 165, 0, 0.15);
}

/* Watch Live Card Styles */
.live-games-list {
  margin: 1rem 0;
  height: 280px;
  width: 100%;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.05);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.no-games {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  opacity: 0.5;
  color: white;
}

.no-games-icon {
  font-size: 3rem;
  margin-bottom: 0.5rem;
}

.games-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.live-game-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem 1rem;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid transparent;
}

.live-game-item:hover {
  background: rgba(78, 204, 163, 0.1);
  border-color: rgba(78, 204, 163, 0.3);
  transform: translateX(4px);
}

.live-players {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-weight: 600;
  font-size: 0.9rem;
}

.vs {
  font-size: 0.7rem;
  color: rgba(255, 255, 255, 0.4);
  text-transform: uppercase;
}

.spectate-btn {
  background: rgba(240, 165, 0, 0.15);
  color: #f0a500;
  border: 1px solid rgba(240, 165, 0, 0.3);
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 0.75rem;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s;
}

.live-game-item:hover .spectate-btn {
  background: rgba(240, 165, 0, 0.3);
}

.join-code-row {
  display: flex;
  gap: 0.5rem;
  width: 100%;
}

.spectate-input {
  flex: 1;
  text-transform: uppercase;
  font-weight: bold;
  letter-spacing: 2px;
}

.spectate-join-btn {
  padding: 8px 16px;
}
</style>
