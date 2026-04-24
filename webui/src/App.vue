<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import ChessBoard from './components/ChessBoard.vue'
import GameControls from './components/GameControls.vue'
import GameStatus from './components/GameStatus.vue'
import ImportExport from './components/ImportExport.vue'
import TimeSettings from './components/TimeSettings.vue'
import MoveHistory from './components/MoveHistory.vue'

// ── Server URL (persisted in localStorage) ────────────────────────────────
const DEFAULT_SERVER = ''
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
const clientRole = ref(localStorage.getItem('chessClientRole') || 'white')

const setRole = (role) => {
  clientRole.value = role
  localStorage.setItem('chessClientRole', role)
}

// ── User / Social State ──────────────────────────────────────────────────
const currentUser = ref(JSON.parse(localStorage.getItem('chessUser')))
const friends = ref([])
const authMode = ref('login') // 'login' or 'register'
const showAuthModal = ref(false)
const authForm = ref({ username: '', password: '' })
const authError = ref('')

const handleAuth = async () => {
  authError.value = ''
  const endpoint = authMode.value === 'login' ? 'login' : 'register'
  try {
    const response = await fetch(`${serverUrl.value}/api/auth/${endpoint}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(authForm.value)
    })
    
    if (response.ok) {
      const user = await response.json()
      currentUser.value = user
      localStorage.setItem('chessUser', JSON.stringify(user))
      showAuthModal.value = false
      fetchFriends()
    } else {
      authError.value = await response.text()
    }
  } catch (e) {
    authError.value = 'Server connection failed'
  }
}

const logout = () => {
  currentUser.value = null
  localStorage.removeItem('chessUser')
  friends.value = []
}

const fetchFriends = async () => {
  if (!currentUser.value) return
  try {
    const response = await fetch(`${serverUrl.value}/api/social/friends?userId=${currentUser.value.id}`)
    if (response.ok) {
      friends.value = await response.json()
    }
  } catch (e) {}
}

const challengeFriend = async (friendId) => {
  try {
    const response = await fetch(`${serverUrl.value}/api/social/challenge`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ friendId })
    })
    if (response.ok) {
      const { partyCode } = await response.json()
      partyCodeInput.value = partyCode
      joinParty()
    }
  } catch (e) {}
}

// ── Party Code Logic ─────────────────────────────────────────────────────
const partyCodeInput = ref('')
const currentParty = ref(localStorage.getItem('chessPartyCode') || '')

const effectiveSessionId = computed(() => currentParty.value || sessionId.value)

const joinParty = () => {
  if (partyCodeInput.value.trim()) {
    currentParty.value = partyCodeInput.value.trim().toUpperCase()
    localStorage.setItem('chessPartyCode', currentParty.value)
    fetchState()
    partyCodeInput.value = ''
  }
}

const leaveParty = () => {
  currentParty.value = ''
  localStorage.removeItem('chessPartyCode')
  fetchState()
}

const applyServer = () => {
  const url = serverInput.value.trim().replace(/\/$/, '')
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
  activePgnParser: 'regex',
  opening: null
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
    const response = await fetch(`${serverUrl.value}/api/state?sessionId=${effectiveSessionId.value}&t=${Date.now()}`)
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
    await fetch(`${serverUrl.value}/api/command?sessionId=${effectiveSessionId.value}`, {
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
  fetchFriends()
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
        <div class="brand-group">
          <h1>JAVI CHESS</h1>
          <div class="premium-tag">Premium Chess Experience</div>
        </div>
        <div class="parser-switcher">
          <label for="parser-select">PGN Parser:</label>
          <select id="parser-select" :value="state.activePgnParser" @change="handleSwitchParser" class="glass-select">
            <option value="regex">Regex</option>
            <option value="fast">Fastparse</option>
            <option value="combinator">Combinator</option>
          </select>
        </div>
        <div v-if="state.opening" class="opening-badge shadow-sm">
          <span class="label">OPENING:</span>
          <span class="name">{{ state.opening }}</span>
        </div>
      </div>

      <!-- Party Mode UI -->
      <div class="server-switcher party-mode">
        <div v-if="!currentParty" class="party-input-group">
          <input 
            v-model="partyCodeInput" 
            placeholder="Party Code (e.g. ABCD)" 
            class="config-input party-input glass-input"
            @keyup.enter="joinParty"
            spellcheck="false"
          >
          <button @click="joinParty" class="server-reset-btn" title="Join Party">Join</button>
        </div>
        <div v-else class="party-active glass-pill">
          <span class="active-label">Party:</span>
          <span class="active-code">{{ currentParty }}</span>
          <button @click="leaveParty" class="server-reset-btn leave-btn" title="Leave Party">✕</button>
        </div>
      </div>

      <!-- Server URL switcher -->
      <div class="server-switcher">
        <span class="server-dot" :class="{ connected: serverConnected, disconnected: !serverConnected }" :title="serverConnected ? 'Connected' : 'Disconnected'"></span>
        <input
          id="server-url-input"
          v-model="serverInput"
          class="server-input glass-input"
          placeholder="Relative (default) or http://host:8080"
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

      <!-- User Account -->
      <div class="user-account">
        <div v-if="!currentUser" class="auth-trigger" @click="showAuthModal = true">
          <span class="user-icon">👤</span>
          <span class="auth-label">Login / Register</span>
        </div>
        <div v-else class="user-profile glass-pill">
          <span class="username">{{ currentUser.username }}</span>
          <button @click="logout" class="logout-btn">Logout</button>
        </div>
      </div>

      <Transition name="slide-down">
        <div v-if="state.message" class="status-msg shadow-lg">{{ state.message }}</div>
      </Transition>
    </header>

    <main>
      <!-- Friends Sidebar -->
      <aside class="social-sidebar glass" v-if="currentUser">
        <div class="sidebar-header">
          <h3>Friends</h3>
        </div>
        <div class="friends-list">
          <div v-for="friend in friends" :key="friend.id" class="friend-item glass-pill">
            <span class="friend-name">{{ friend.username }}</span>
            <button @click="challengeFriend(friend.id)" class="challenge-btn">Challenge</button>
          </div>
          <div v-if="friends.length === 0" class="no-friends">
            <p>No friends yet.</p>
          </div>
        </div>
      </aside>

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

  <!-- Auth Modal -->
  <div v-if="showAuthModal" class="modal-overlay" @click.self="showAuthModal = false">
    <div class="modal-content glass">
      <h2>{{ authMode === 'login' ? 'Welcome Back' : 'Join Javi Chess' }}</h2>
      <div class="auth-tabs">
        <button :class="{ active: authMode === 'login' }" @click="authMode = 'login'">Login</button>
        <button :class="{ active: authMode === 'register' }" @click="authMode = 'register'">Register</button>
      </div>
      
      <form @submit.prevent="handleAuth" class="auth-form">
        <div class="form-group">
          <input v-model="authForm.username" placeholder="Username" required class="glass-input">
        </div>
        <div class="form-group">
          <input v-model="authForm.password" type="password" placeholder="Password" required class="glass-input">
        </div>
        <p v-if="authError" class="auth-error">{{ authError }}</p>
        <button type="submit" class="auth-submit-btn">{{ authMode === 'login' ? 'Login' : 'Sign Up' }}</button>
      </form>
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
  font-size: 1.8rem;
  letter-spacing: 3px;
  color: var(--primary);
  line-height: 1;
  font-family: 'Playfair Display', serif;
}

.brand-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.premium-tag {
  font-size: 0.65rem;
  text-transform: uppercase;
  letter-spacing: 2px;
  color: var(--accent);
  opacity: 0.8;
  font-weight: 400;
  margin-left: 2px;
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
.opening-badge {
  background: rgba(78, 204, 163, 0.1);
  border: 1px solid rgba(78, 204, 163, 0.3);
  padding: 4px 12px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.85rem;
}
.opening-badge .label {
  color: var(--primary);
  font-weight: 700;
  font-size: 0.75rem;
}
.opening-badge .name {
  color: white;
  font-weight: 600;
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
  padding: 1rem;
  gap: 1rem;
  overflow: hidden;
}

.left-panel, .right-panel {
  width: 320px;
  min-width: 300px;
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
  width: 100%;
  max-width: 650px;
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
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.8);
  backdrop-filter: blur(8px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-content {
  width: 100%;
  max-width: 400px;
  padding: 2.5rem;
  border-radius: 30px;
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
.party-mode {
  flex: 0 1 auto;
}

.party-input-group {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}

.party-input {
  width: 150px;
  text-transform: uppercase;
  font-weight: 600;
  letter-spacing: 0.05em;
  text-align: center;
}

.party-active {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.4rem 1rem;
  background: rgba(var(--primary-rgb), 0.15) !important;
  border: 1px solid rgba(var(--primary-rgb), 0.3) !important;
}

.active-label {
  font-size: 0.75rem;
  text-transform: uppercase;
  opacity: 0.7;
  font-weight: 600;
}

.active-code {
  font-family: 'Space Mono', monospace;
  font-weight: 700;
  color: var(--primary);
  font-size: 1.1rem;
  letter-spacing: 0.1em;
}

.leave-btn {
  color: #ff4d4d;
  font-size: 1.2rem;
  padding: 0;
  background: none;
  border: none;
}

.leave-btn:hover {
  color: #ff1a1a;
  transform: scale(1.2);
}

/* Social & Auth Styles */
.user-account {
  margin-left: 2rem;
}

.auth-trigger {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1.2rem;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.05);
  transition: all 0.2s;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.auth-trigger:hover {
  background: rgba(var(--primary-rgb), 0.1);
  border-color: rgba(var(--primary-rgb), 0.3);
}

.user-profile {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.4rem 1.2rem;
}

.logout-btn {
  font-size: 0.7rem;
  opacity: 0.6;
  background: none;
  border: none;
  color: white;
  cursor: pointer;
  text-decoration: underline;
}

/* Sidebar */
.social-sidebar {
  width: 240px;
  min-width: 240px;
  padding: 1.5rem;
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  height: fit-content;
}

.sidebar-header h3 {
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.2em;
  opacity: 0.6;
  margin: 0;
}

.friend-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.75rem;
  padding: 0.75rem 1rem;
  background: rgba(255, 255, 255, 0.03);
}

.challenge-btn {
  font-size: 0.7rem;
  background: var(--primary);
  color: black;
  border: none;
  border-radius: 6px;
  padding: 0.4rem 0.8rem;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.2s;
}

.challenge-btn:hover {
  transform: translateY(-2px);
  filter: brightness(1.1);
}

.auth-tabs {
  display: flex;
  gap: 1.5rem;
  justify-content: center;
  margin-bottom: 2rem;
}

.auth-submit-btn {
  background: var(--primary);
  color: black;
  border: none;
  padding: 1rem;
  border-radius: 14px;
  font-weight: 700;
  margin-top: 1rem;
  cursor: pointer;
  transition: all 0.2s;
}

.auth-submit-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 20px rgba(var(--primary-rgb), 0.3);
}

.no-friends {
  text-align: center;
  opacity: 0.4;
  font-size: 0.8rem;
  padding: 2rem 0;
}

</style>
