<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ChessApi, type Color, type StateDto } from './api'
import ChessBoard from './components/ChessBoard.vue'

const state = ref<StateDto | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const message = ref<string | null>(null)

const from = ref('')
const to = ref('')
const promotion = ref('')
const selected = ref<{ from?: string; to?: string }>({})
const highlights = ref<string[]>([])

// Simple client-side chess clock (default 5 minutes per side)
const initialSeconds = 5 * 60
const whiteSeconds = ref(initialSeconds)
const blackSeconds = ref(initialSeconds)
let clockTimer: number | null = null

const isGameOver = computed(() => {
  const s = state.value?.status ?? ''
  return s.startsWith('checkmate') || s === 'stalemate' || s.startsWith('draw')
})

function formatClock(totalSeconds: number): string {
  const m = Math.floor(totalSeconds / 60)
  const s = totalSeconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

const whiteClock = computed(() => formatClock(whiteSeconds.value))
const blackClock = computed(() => formatClock(blackSeconds.value))

const lastMove = computed(() => {
  const lm = state.value?.lastMove
  if (!lm) return undefined
  return { from: lm.from, to: lm.to }
})

function stopClock() {
  if (clockTimer != null) {
    window.clearInterval(clockTimer)
    clockTimer = null
  }
}

function ensureClockRunning() {
  if (clockTimer != null) return
  clockTimer = window.setInterval(() => {
    const st = state.value
    if (!st) return
    if (isGameOver.value) return
    if (loading.value) return
    if (st.activeColor === 'white') {
      whiteSeconds.value = Math.max(0, whiteSeconds.value - 1)
    } else {
      blackSeconds.value = Math.max(0, blackSeconds.value - 1)
    }
  }, 1000)
}

function resetClock() {
  whiteSeconds.value = initialSeconds
  blackSeconds.value = initialSeconds
}

async function refresh() {
  loading.value = true
  error.value = null
  try {
    state.value = await ChessApi.state()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function doMove() {
  if (!from.value || !to.value) return
  loading.value = true
  error.value = null
  message.value = null
  try {
    const res = await ChessApi.move({
      from: from.value.trim(),
      to: to.value.trim(),
      promotion: promotion.value.trim() || undefined,
    })
    state.value = res.state
    message.value = res.message ?? null
    selected.value = {}
    highlights.value = []
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function runCmd(fn: () => Promise<{ state: StateDto; message?: string }>) {
  loading.value = true
  error.value = null
  message.value = null
  try {
    const res = await fn()
    state.value = res.state
    message.value = res.message ?? null
    if (fn === ChessApi.newGame) {
      resetClock()
      selected.value = {}
      highlights.value = []
      from.value = ''
      to.value = ''
      promotion.value = ''
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function loadMoves(square: string) {
  try {
    const res = await ChessApi.moves(square)
    highlights.value = res.targets
  } catch {
    highlights.value = []
  }
}

function isOwnPieceSquare(square: string, color: Color): boolean {
  const p = state.value?.pieces.find((x) => x.pos === square)
  return p?.color === color
}

function onSquareClick(square: string) {
  if (loading.value) return
  if (!state.value) return

  // Start new selection
  if (!selected.value.from || (selected.value.from && selected.value.to)) {
    // Only allow selecting own piece
    if (!isOwnPieceSquare(square, state.value.activeColor)) return
    selected.value = { from: square }
    from.value = square
    to.value = ''
    void loadMoves(square)
    return
  }

  // Second click: set destination (or reset if same)
  if (selected.value.from === square) {
    selected.value = {}
    from.value = ''
    to.value = ''
    highlights.value = []
    return
  }

  // If user clicks another of their own pieces, switch "from"
  if (isOwnPieceSquare(square, state.value.activeColor)) {
    selected.value = { from: square }
    from.value = square
    to.value = ''
    void loadMoves(square)
    return
  }

  // Only allow clicking highlighted targets
  if (!highlights.value.includes(square)) return

  selected.value = { from: selected.value.from, to: square }
  to.value = square
  void doMove()
}

onMounted(refresh)
watch(
  () => state.value?.activeColor,
  () => {
    if (!state.value) return
    ensureClockRunning()
  }
)

watch(isGameOver, (over) => {
  if (over) stopClock()
})

onBeforeUnmount(() => stopClock())
</script>

<template>
  <div class="page">
    <header class="header">
      <div class="title">
        <div class="appname">Chess Web UI</div>
        <div class="meta">
          <span v-if="state">Turn: <b>{{ state.activeColor }}</b></span>
          <span v-if="state">Status: <b>{{ state.status }}</b></span>
        </div>
      </div>

      <div class="actions">
        <button class="btn" :disabled="loading" @click="runCmd(ChessApi.newGame)">New</button>
        <button class="btn" :disabled="loading" @click="runCmd(ChessApi.undo)">Undo</button>
        <button class="btn" :disabled="loading" @click="runCmd(ChessApi.draw)">Draw</button>
        <button class="btn danger" :disabled="loading" @click="runCmd(ChessApi.resign)">Resign</button>
      </div>
    </header>

    <main class="main">
      <section class="boardWrap">
        <ChessBoard
          v-if="state"
          :pieces="state.pieces"
          :last-move="lastMove"
          :selected="selected"
          :highlights="highlights"
          @square-click="onSquareClick"
        />
        <div v-else class="placeholder">
          <div v-if="loading">Loading…</div>
          <div v-else>Backend not reachable.</div>
        </div>
      </section>

      <section class="panel">
        <div class="card">
          <div class="cardTitle">Clock</div>
          <div class="clocks">
            <div class="clockRow" :class="{ active: state?.activeColor === 'white' }">
              <div class="side">White</div>
              <div class="time">{{ whiteClock }}</div>
            </div>
            <div class="clockRow" :class="{ active: state?.activeColor === 'black' }">
              <div class="side">Black</div>
              <div class="time">{{ blackClock }}</div>
            </div>
          </div>
          <div class="clockActions">
            <button class="btn" :disabled="loading" @click="resetClock">Reset</button>
          </div>
        </div>

        <div class="card">
          <div class="cardTitle">Make move</div>
          <div class="form">
            <label>
              From
              <input v-model="from" placeholder="e2" maxlength="2" />
            </label>
            <label>
              To
              <input v-model="to" placeholder="e4" maxlength="2" />
            </label>
            <label>
              Promotion
              <input v-model="promotion" placeholder="q/r/b/n (optional)" />
            </label>
            <button class="btn primary" :disabled="loading" @click="doMove">Send</button>
          </div>
          <div v-if="message" class="msg ok">{{ message }}</div>
          <div v-if="error" class="msg err">{{ error }}</div>
        </div>

        <div class="card">
          <div class="cardTitle">Dev tips</div>
          <div class="hint">
            Start Scala API with <code>sbt runMain chess.web.WebMain</code> and the UI with
            <code>cd webui && npm i && npm run dev</code>.
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24px;
  color: rgba(255, 255, 255, 0.92);
  background:
    radial-gradient(1200px 800px at 20% 10%, rgba(97, 218, 251, 0.18), transparent 60%),
    radial-gradient(900px 700px at 80% 20%, rgba(167, 139, 250, 0.16), transparent 60%),
    linear-gradient(180deg, #0b1020 0%, #070a12 100%);
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.title {
  display: grid;
  gap: 6px;
}

.appname {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.2px;
}

.meta {
  display: flex;
  gap: 14px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.72);
}

.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.main {
  display: grid;
  grid-template-columns: 1fr 360px;
  gap: 22px;
  align-items: start;
}

@media (max-width: 980px) {
  .main {
    grid-template-columns: 1fr;
  }
}

.boardWrap {
  display: grid;
  place-items: start;
}

.placeholder {
  width: min(92vw, 560px);
  aspect-ratio: 1 / 1;
  border-radius: 14px;
  border: 1px dashed rgba(255, 255, 255, 0.2);
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, 0.65);
}

.panel {
  display: grid;
  gap: 14px;
}

.card {
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.04);
  backdrop-filter: blur(10px);
  padding: 14px;
}

.cardTitle {
  font-weight: 700;
  margin-bottom: 10px;
}

.form {
  display: grid;
  gap: 10px;
}

label {
  display: grid;
  gap: 6px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.72);
}

input {
  height: 38px;
  border-radius: 10px;
  padding: 0 12px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(0, 0, 0, 0.2);
  color: rgba(255, 255, 255, 0.92);
  outline: none;
}

input:focus {
  border-color: rgba(97, 218, 251, 0.55);
  box-shadow: 0 0 0 4px rgba(97, 218, 251, 0.12);
}

.btn {
  height: 38px;
  border-radius: 10px;
  padding: 0 12px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.9);
  cursor: pointer;
}

.btn:hover {
  background: rgba(255, 255, 255, 0.09);
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.primary {
  border-color: rgba(97, 218, 251, 0.35);
  background: rgba(97, 218, 251, 0.12);
}

.danger {
  border-color: rgba(248, 113, 113, 0.35);
  background: rgba(248, 113, 113, 0.12);
}

.msg {
  margin-top: 10px;
  font-size: 13px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
}

.ok {
  border-color: rgba(52, 211, 153, 0.25);
  background: rgba(52, 211, 153, 0.1);
}

.err {
  border-color: rgba(248, 113, 113, 0.25);
  background: rgba(248, 113, 113, 0.1);
}

.hint {
  color: rgba(255, 255, 255, 0.72);
  font-size: 13px;
  line-height: 1.35;
}

.clocks {
  display: grid;
  gap: 10px;
}

.clockRow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 10px 12px;
  background: rgba(0, 0, 0, 0.18);
}

.clockRow.active {
  border-color: rgba(97, 218, 251, 0.35);
  box-shadow: 0 0 0 4px rgba(97, 218, 251, 0.12);
}

.side {
  font-weight: 700;
  color: rgba(255, 255, 255, 0.8);
}

.time {
  font-variant-numeric: tabular-nums;
  font-size: 18px;
  font-weight: 800;
  letter-spacing: 0.5px;
}

.clockActions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}

code {
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 2px 6px;
  border-radius: 8px;
}
</style>
