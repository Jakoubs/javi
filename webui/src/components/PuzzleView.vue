<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import ChessBoard from './ChessBoard.vue'
import MoveHistory from './MoveHistory.vue'

const props = defineProps(['serverUrl'])

// State
const themes = ref([])
const selectedTheme = ref('')
const sortOrder = ref('asc')
const puzzleList = ref([])
const currentPuzzle = ref(null)
const puzzleFen = ref('')
const solutionIndex = ref(0)
const playerColor = ref('white')
const message = ref('')
const messageType = ref('')
const solved = ref(false)
const loading = ref(false)
const highlights = ref([])
const selectedPos = ref(null)
const showList = ref(false)
const hintState = ref(0) // 0: no hint, 1: show from, 2: show full move
const hintHighlights = ref([])
const history = ref([])
const fens = ref([])
const viewIndex = ref(0)

// Board state from FEN
const boardFen = computed(() => fens.value[viewIndex.value] || puzzleFen.value || 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1')
const isFlipped = computed(() => playerColor.value === 'black')

// Fetch themes
const fetchThemes = async () => {
  try {
    const res = await fetch(`${props.serverUrl}/api/puzzles/themes`)
    if (res.ok) themes.value = await res.json()
  } catch (e) { console.error('Failed to fetch themes', e) }
}

// Fetch puzzle list by theme
const fetchPuzzleList = async () => {
  if (!selectedTheme.value) return
  loading.value = true
  try {
    const res = await fetch(`${props.serverUrl}/api/puzzles?theme=${selectedTheme.value}&order=${sortOrder.value}&limit=50`)
    if (res.ok) {
      puzzleList.value = await res.json()
      showList.value = true
    }
  } catch (e) { console.error(e) }
  loading.value = false
}

// Fetch random puzzle
const fetchRandom = async () => {
  loading.value = true
  const themeQ = selectedTheme.value ? `?theme=${selectedTheme.value}` : ''
  try {
    const res = await fetch(`${props.serverUrl}/api/puzzles/random${themeQ}`)
    if (res.ok) {
      const puzzle = await res.json()
      startPuzzle(puzzle)
    } else {
      message.value = 'No puzzles found for this theme.'
      messageType.value = 'error'
    }
  } catch (e) { message.value = 'Connection error'; messageType.value = 'error' }
  loading.value = false
}

// Start a puzzle
const startPuzzle = (puzzle) => {
  currentPuzzle.value = puzzle
  solved.value = false
  solutionIndex.value = 0
  highlights.value = []
  hintHighlights.value = []
  selectedPos.value = null
  showList.value = false
  hintState.value = 0
  history.value = []
  fens.value = [puzzle.fen]
  viewIndex.value = 0

  const fenParts = puzzle.fen.split(' ')
  const fenTurn = fenParts[1]
  playerColor.value = fenTurn === 'w' ? 'black' : 'white'

  puzzleFen.value = puzzle.fen
  message.value = `Find the best move for ${playerColor.value}!`
  messageType.value = 'info'

  setTimeout(() => {
    applyUciMove(puzzle.solution[0])
    solutionIndex.value = 1
    message.value = `${playerColor.value === 'white' ? 'White' : 'Black'}'s turn!`
    messageType.value = 'info'
  }, 800)
}

// Apply a UCI move to the current FEN
const applyUciMove = (uci) => {
  const fen = puzzleFen.value
  const parts = fen.split(' ')
  const board = parseFenBoard(parts[0])
  const from = uciToCoords(uci.substring(0, 2))
  const to = uciToCoords(uci.substring(2, 4))
  const promo = uci.length > 4 ? uci[4] : null

  const piece = board[from.r][from.c]
  board[from.r][from.c] = null
  
  if (piece && piece.toLowerCase() === 'p' && from.c !== to.c && !board[to.r][to.c]) {
    board[from.r][to.c] = null
  }
  
  if (piece && piece.toLowerCase() === 'k' && Math.abs(from.c - to.c) === 2) {
    if (to.c === 6) { board[to.r][5] = board[to.r][7]; board[to.r][7] = null }
    if (to.c === 2) { board[to.r][3] = board[to.r][0]; board[to.r][0] = null }
  }

  if (promo) {
    const isWhite = piece === piece.toUpperCase()
    board[to.r][to.c] = isWhite ? promo.toUpperCase() : promo.toLowerCase()
  } else {
    board[to.r][to.c] = piece
  }

  const activeColor = parts[1] === 'w' ? 'b' : 'w'
  const newFen = boardToFen(board) + ' ' + activeColor + ' - - 0 1'
  puzzleFen.value = newFen

  history.value.push(uci)
  fens.value.push(newFen)
  viewIndex.value = fens.value.length - 1

  highlights.value = []
  hintHighlights.value = []
  selectedPos.value = null
}

const handleJump = (idx) => {
  viewIndex.value = idx
}

const handleHistoryCommand = (cmd) => {
  if (cmd === 'back' && viewIndex.value > 0) viewIndex.value--
  if (cmd === 'forward' && viewIndex.value < fens.value.length - 1) viewIndex.value++
  if (cmd === 'first') viewIndex.value = 0
  if (cmd === 'last') viewIndex.value = fens.value.length - 1
}

const parseFenBoard = (fenBoard) => {
  const rows = fenBoard.split('/')
  return rows.map(row => {
    const cells = []
    for (const ch of row) {
      if (isNaN(ch)) cells.push(ch)
      else for (let i = 0; i < parseInt(ch); i++) cells.push(null)
    }
    return cells
  })
}

const boardToFen = (board) => {
  return board.map(row => {
    let fen = '', empty = 0
    for (const cell of row) {
      if (!cell) empty++
      else { if (empty) { fen += empty; empty = 0 }; fen += cell }
    }
    if (empty) fen += empty
    return fen
  }).join('/')
}

const uciToCoords = (sq) => ({ r: 8 - parseInt(sq[1]), c: sq.charCodeAt(0) - 97 })
const coordsToAlg = (r, c) => String.fromCharCode(97 + c) + (8 - r)

const handleHint = () => {
  if (solved.value || !currentPuzzle.value || solutionIndex.value === 0) return
  const expectedMove = currentPuzzle.value.solution[solutionIndex.value]
  if (!expectedMove) return
  if (hintState.value === 0) {
    hintState.value = 1
    const fromSquare = coordsToAlg(uciToCoords(expectedMove.substring(0, 2)).r, uciToCoords(expectedMove.substring(0, 2)).c)
    hintHighlights.value = [fromSquare]
  } else if (hintState.value === 1) {
    hintState.value = 2
    const toSquare = coordsToAlg(uciToCoords(expectedMove.substring(2, 4)).r, uciToCoords(expectedMove.substring(2, 4)).c)
    const fromSquare = coordsToAlg(uciToCoords(expectedMove.substring(0, 2)).r, uciToCoords(expectedMove.substring(0, 2)).c)
    hintHighlights.value = [fromSquare, toSquare]
  }
}

const handleSquareClick = async (pos) => {
  if (solved.value || !currentPuzzle.value || solutionIndex.value === 0) return
  if (viewIndex.value !== fens.value.length - 1) return

  const board = parseFenBoard(puzzleFen.value.split(' ')[0])
  const coords = uciToCoords(pos)
  const piece = board[coords.r][coords.c]
  const isPlayerPiece = piece && (
    (playerColor.value === 'white' && piece === piece.toUpperCase()) ||
    (playerColor.value === 'black' && piece === piece.toLowerCase())
  )

  if (selectedPos.value && highlights.value.includes(pos)) {
    const uciMove = selectedPos.value + pos
    const expectedMove = currentPuzzle.value.solution[solutionIndex.value]
    
    if (uciMove === expectedMove || uciMove === expectedMove.substring(0, 4)) {
      applyUciMove(expectedMove)
      solutionIndex.value++
      if (solutionIndex.value >= currentPuzzle.value.solution.length) {
        message.value = '🎉 Puzzle solved!'
        messageType.value = 'success'
        solved.value = true
      } else {
        message.value = 'Correct! Opponent responds...'
        messageType.value = 'success'
        setTimeout(() => {
          applyUciMove(currentPuzzle.value.solution[solutionIndex.value])
          solutionIndex.value++
          if (solutionIndex.value >= currentPuzzle.value.solution.length) {
            message.value = '🎉 Puzzle solved!'
            messageType.value = 'success'
            solved.value = true
          } else {
            message.value = `${playerColor.value === 'white' ? 'White' : 'Black'}'s turn!`
            messageType.value = 'info'
            hintState.value = 0
            hintHighlights.value = []
          }
        }, 600)
      }
    } else {
      message.value = '❌ Wrong move. Try again!'
      messageType.value = 'error'
    }
    selectedPos.value = null
    highlights.value = []
  } else if (isPlayerPiece) {
    selectedPos.value = pos
    try {
      const fen = encodeURIComponent(puzzleFen.value)
      const res = await fetch(`${props.serverUrl}/api/puzzles/legal-moves?fen=${fen}&square=${pos}`)
      if (res.ok) {
        const legalMoves = await res.json()
        highlights.value = legalMoves
      }
    } catch (e) { highlights.value = [] }
  } else {
    selectedPos.value = null
    highlights.value = []
  }
}

const currentThemeInfo = computed(() => {
  if (!currentPuzzle.value) return null
  const themeKeys = currentPuzzle.value.themes || []
  return themes.value.filter(t => themeKeys.includes(t.key))
})

watch(selectedTheme, () => { puzzleList.value = []; showList.value = false })
onMounted(fetchThemes)
</script>

<template>
  <div class="puzzle-view-layout">
    
    <!-- LEFT SIDEBAR -->
    <div class="side-area left">
      <div class="puzzle-status glass-panel">
        <div class="status-top">
          <span class="label">PLAYER TO MOVE</span>
          <div :class="['turn-indicator', playerColor]">{{ playerColor === 'white' ? 'White' : 'Black' }}</div>
        </div>
        <div v-if="message" :class="['status-message', messageType]">
          {{ message }}
        </div>
      </div>

      <div class="history-wrapper">
        <MoveHistory 
          :moves="history" 
          :viewIndex="viewIndex" 
          @jump="handleJump"
          @command="handleHistoryCommand"
        />
      </div>


    </div>

    <!-- CENTER BOARD -->
    <div class="board-area">
      <div class="player-label top">{{ isFlipped ? 'White' : 'Black' }}</div>
      <div class="main-board">
        <ChessBoard
          :fen="boardFen"
          :flipped="isFlipped"
          :highlights="highlights"
          :hintHighlights="hintHighlights"
          :selectedPos="selectedPos"
          @square-click="handleSquareClick"
        />
      </div>
      <div class="player-label bottom">{{ isFlipped ? 'Black' : 'White' }} (You)</div>
      
      <div class="board-actions">
        <button v-if="solved" @click="fetchRandom" class="btn primary massive-btn">Next Puzzle →</button>
      </div>
    </div>

    <!-- RIGHT SIDEBAR -->
    <div class="side-area right">
      <div class="puzzle-controls-box glass-panel">
        <h2 class="title">♟ Puzzle Training</h2>

        <div class="control-group">
          <label>Theme</label>
          <select v-model="selectedTheme" class="glass-select">
            <option value="">All Themes</option>
            <option v-for="t in themes" :key="t.key" :value="t.key">{{ t.name }}</option>
          </select>
        </div>

        <div class="control-group">
          <label>Difficulty</label>
          <select v-model="sortOrder" class="glass-select">
            <option value="asc">Easiest First</option>
            <option value="desc">Hardest First</option>
          </select>
        </div>

        <div class="action-buttons">
          <button @click="fetchRandom" class="btn primary" :disabled="loading">
            {{ loading ? '...' : '🎲 Random' }}
          </button>
          <button @click="fetchPuzzleList" class="btn" :disabled="!selectedTheme || loading">
            📋 Browse
          </button>
        </div>

        <div class="hint-zone" v-if="!solved && currentPuzzle && solutionIndex > 0">
          <button @click="handleHint" class="btn hint-btn" :disabled="hintState >= 2">
            💡 Hint {{ hintState > 0 ? `(${hintState}/2)` : '' }}
          </button>
        </div>

        <div v-if="currentPuzzle" class="puzzle-meta">

          <div class="meta-row">
            <span class="meta-label">Rating</span>
            <span class="badge">{{ currentPuzzle.rating }}</span>
          </div>
          <div class="meta-tags">
            <span v-for="ti in currentThemeInfo" :key="ti.key" class="tag" :title="ti.description">{{ ti.name }}</span>
          </div>
        </div>
      </div>

      <div v-if="showList && puzzleList.length" class="puzzle-list-container glass-panel">
        <h3>Puzzles — {{ themes.find(t => t.key === selectedTheme)?.name }}</h3>
        <div class="list-scroll">
          <div
            v-for="(p, index) in puzzleList" :key="p.id"
            class="puzzle-list-item"
            :class="{ active: currentPuzzle?.id === p.id }"
            @click="startPuzzle(p)"
          >
            <span class="pid">{{ selectedTheme ? themes.find(t => t.key === selectedTheme)?.name : 'Puzzle' }} #{{ index + 1 }}</span>
            <span class="prating">{{ p.rating }}</span>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>

<style scoped>
.puzzle-view-layout {
  display: flex;
  justify-content: center;
  align-items: flex-start;
  width: 100%;
  height: calc(100vh - 80px);
  padding: 2rem;
  box-sizing: border-box;
  gap: 3rem;
  overflow: hidden;
}

.side-area {
  width: 340px;
  min-width: 340px;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  height: 80vh;
}

.side-area.left {
  /* Aligns with board top */
}

.side-area.right {
  /* Aligns with board top */
}

.board-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  flex-shrink: 0;
}

.main-board {
  padding: 10px;
  background: rgba(255,255,255,0.02);
  border-radius: 12px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.3);
}

.puzzle-status {
  padding: 1.25rem;
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.history-wrapper {
  flex: 1;
  overflow: hidden;
  display: flex;
  border-radius: 16px;
}

.puzzle-controls-box {
  padding: 1.5rem;
  border-radius: 20px;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.title {
  margin: 0;
  font-size: 1.5rem;
  color: var(--primary);
  letter-spacing: 1px;
}

.control-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.control-group label {
  font-size: 0.7rem;
  text-transform: uppercase;
  color: rgba(255,255,255,0.4);
  font-weight: 700;
  letter-spacing: 1px;
}

.action-buttons {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}

.hint-zone {
  margin-top: 0.5rem;
}

.hint-btn {
  width: 100%;
  background: rgba(240, 165, 0, 0.1);
  color: var(--accent);
  border: 1px solid rgba(240, 165, 0, 0.2);
}

.puzzle-meta {
  margin-top: 0.5rem;
  padding-top: 1rem;
  border-top: 1px solid rgba(255,255,255,0.05);
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.meta-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.9rem;
}

.meta-label { color: rgba(255,255,255,0.4); }

.badge {
  background: var(--primary);
  color: #1a1a2e;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 800;
  font-size: 0.8rem;
}

.meta-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag {
  font-size: 0.7rem;
  padding: 4px 8px;
  background: rgba(255,255,255,0.05);
  border-radius: 4px;
  color: rgba(255,255,255,0.7);
}

.puzzle-list-container {
  flex: 1;
  padding: 1rem;
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  overflow: hidden;
}

.puzzle-list-container h3 { margin: 0; font-size: 0.85rem; color: var(--primary); text-transform: uppercase; }

.list-scroll { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }

.puzzle-list-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  background: rgba(255,255,255,0.02);
  font-size: 0.85rem;
}

.puzzle-list-item:hover { background: rgba(255,255,255,0.06); }
.puzzle-list-item.active { background: rgba(78,204,163,0.1); border: 1px solid var(--primary); }

.glass-panel {
  background: rgba(255, 255, 255, 0.03);
  backdrop-filter: blur(15px);
  border: 1px solid rgba(255, 255, 255, 0.05);
  box-shadow: 0 8px 32px rgba(0,0,0,0.2);
}

.status-top { display: flex; flex-direction: column; gap: 4px; }
.label { font-size: 0.65rem; color: rgba(255,255,255,0.4); font-weight: 700; letter-spacing: 1px; }
.turn-indicator { font-size: 1.25rem; font-weight: 800; text-transform: uppercase; }
.turn-indicator.white { color: #fff; }
.turn-indicator.black { color: #a0aec0; }
.status-message { font-size: 0.85rem; font-weight: 600; padding: 10px; border-radius: 8px; margin-top: 4px; }
.status-message.success { background: rgba(78, 204, 163, 0.1); color: #4ecca3; }
.status-message.error { background: rgba(245, 101, 101, 0.1); color: #f56565; }
.status-message.info { background: rgba(99, 179, 237, 0.1); color: #63b3ed; }

.player-label { font-size: 0.75rem; font-weight: 700; text-transform: uppercase; color: rgba(255,255,255,0.3); letter-spacing: 2px; }
.player-label.bottom { color: var(--primary); }

.massive-btn { width: 100%; margin-top: 1rem; }
</style>
