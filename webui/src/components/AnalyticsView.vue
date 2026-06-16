<script setup>
import { ref, onMounted } from 'vue'

const props = defineProps({
  serverUrl: {
    type: String,
    required: true
  }
})

const loading = ref(true)
const errorMsg = ref(null)
const data = ref({
  gameResults: [],
  popularFirstMoves: [],
  puzzleDifficulty: [],
  puzzleThemes: [],
  gamesOverTime: []
})

const fetchAnalytics = async () => {
  loading.value = true
  errorMsg.value = null
  try {
    const response = await fetch(`${props.serverUrl}/api/analytics/summary`)
    if (response.ok) {
      data.value = await response.json()
    } else {
      errorMsg.value = 'Fehler beim Laden der Spieldaten vom Server.'
    }
  } catch (e) {
    console.error(e)
    errorMsg.value = 'Server konnte nicht erreicht werden.'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchAnalytics()
})

const isDataEmpty = () => {
  return !data.value.gameResults.length &&
         !data.value.popularFirstMoves.length &&
         !data.value.puzzleDifficulty.length &&
         !data.value.puzzleThemes.length &&
         !data.value.gamesOverTime.length
}

const getWinnerColor = (winner) => {
  if (winner.toLowerCase().includes('white')) return '#4ecca3'
  if (winner.toLowerCase().includes('black')) return '#ff6b6b'
  return '#f0a500'
}

const formatPercent = (val) => {
  return typeof val === 'number' ? val.toFixed(1) : '0.0'
}
</script>

<template>
  <div class="analytics-container">
    <div class="analytics-header">
      <div class="title-section">
        <h2>♟ Spark Analytics Dashboard</h2>
        <p class="subtitle">Echtzeit- und Batch-Analysen aus Spark-Aggregations-Pipelines</p>
      </div>
      <button @click="fetchAnalytics" class="refresh-btn" :disabled="loading">
        <span v-if="loading" class="spinner"></span>
        <span>🔄 Aktualisieren</span>
      </button>
    </div>

    <!-- Loading state -->
    <div v-if="loading && isDataEmpty()" class="loading-state glass">
      <div class="loader"></div>
      <p>Analysedaten werden aus der PostgreSQL geladen...</p>
    </div>

    <!-- Error state -->
    <div v-else-if="errorMsg" class="error-state glass">
      <span class="error-icon">⚠️</span>
      <p>{{ errorMsg }}</p>
      <button @click="fetchAnalytics" class="btn primary">Erneut versuchen</button>
    </div>

    <!-- Empty state (Spark hasn't run yet) -->
    <div v-else-if="isDataEmpty()" class="empty-state glass">
      <div class="empty-icon">📊</div>
      <h3>Keine Spark-Analysedaten gefunden</h3>
      <p>Die Analysetabellen in der Datenbank sind noch leer. Bitte stelle sicher, dass:</p>
      <ul class="guide-list">
        <li>Der Spark-Dockercontainer gestartet wurde und läuft.</li>
        <li>Spiele absolviert wurden, um historische Daten zu generieren.</li>
        <li>Der Spark Batch-Job ausgeführt wurde, um die CSV/Postgres-Daten zu aggregieren.</li>
      </ul>
      <p class="spark-cmd-info">Tipp: Führe <code>docker compose --profile spark up -d</code> aus, um die Spark-Pipeline zu starten.</p>
      <button @click="fetchAnalytics" class="btn primary">Jetzt prüfen</button>
    </div>

    <!-- Dashboard Content -->
    <div v-else class="dashboard-grid">
      <!-- Row 1: Game Results & Popular Openings -->
      <div class="row-1">
        <!-- Game Results -->
        <div class="card glass game-results-card">
          <h3>🏆 Spielresultate</h3>
          <div class="results-visual">
            <div 
              v-for="r in data.gameResults" 
              :key="r.result" 
              class="result-segment"
              :style="{ width: r.percentage + '%', backgroundColor: getWinnerColor(r.result) }"
              :title="`${r.result}: ${r.count} Spiele (${formatPercent(r.percentage)}%)`"
            ></div>
          </div>
          <div class="results-legend">
            <div v-for="r in data.gameResults" :key="r.result" class="legend-item">
              <span class="color-dot" :style="{ backgroundColor: getWinnerColor(r.result) }"></span>
              <span class="legend-name">{{ r.result }}</span>
              <span class="legend-val">{{ r.count }} ({{ formatPercent(r.percentage) }}%)</span>
            </div>
          </div>
        </div>

        <!-- Popular Openings -->
        <div class="card glass openings-card">
          <h3>📖 Beliebteste Eröffnungszüge</h3>
          <div class="openings-list">
            <div v-for="(op, index) in data.popularFirstMoves" :key="op.san" class="opening-item">
              <span class="opening-rank">#{{ index + 1 }}</span>
              <span class="opening-name">{{ op.san }}</span>
              <div class="bar-container">
                <div 
                  class="bar" 
                  :style="{ width: (op.count / data.popularFirstMoves[0].count) * 100 + '%' }"
                ></div>
              </div>
              <span class="opening-count">{{ op.count }}x</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Row 2: Puzzle Difficulties -->
      <div class="card glass difficulty-card">
        <h3>🎯 Lichess-Puzzles nach Schwierigkeitsgrad</h3>
        <div class="difficulty-grid">
          <div v-for="diff in data.puzzleDifficulty" :key="diff.difficulty" class="diff-box">
            <span class="diff-title">{{ diff.difficulty }}</span>
            <span class="diff-count">{{ diff.count.toLocaleString() }} Puzzles</span>
            <div class="diff-details">
              <div><span>Schnitt:</span> <strong>{{ Math.round(diff.avgRating) }}</strong> Rating</div>
              <div><span>Züge Ø:</span> <strong>{{ diff.avgMoves.toFixed(1) }}</strong></div>
            </div>
          </div>
        </div>
      </div>

      <!-- Row 3: Puzzle Themes & Games Played Over Time -->
      <div class="row-3">
        <!-- Themes -->
        <div class="card glass themes-card">
          <h3>🏷️ Top Puzzle-Motive</h3>
          <div class="themes-list">
            <div v-for="t in data.puzzleThemes" :key="t.theme" class="theme-row">
              <span class="theme-name">{{ t.theme }}</span>
              <div class="theme-bar-wrap">
                <div 
                  class="theme-bar"
                  :style="{ width: (t.count / data.puzzleThemes[0].count) * 100 + '%' }"
                ></div>
              </div>
              <span class="theme-count">{{ t.count.toLocaleString() }}</span>
              <span class="theme-rating">Ø {{ Math.round(t.avgRating) }}</span>
            </div>
          </div>
        </div>

        <!-- Games Over Time -->
        <div class="card glass games-time-card">
          <h3>📅 Spieleentwicklung über Zeit</h3>
          <div class="time-list-wrapper">
            <table class="time-table">
              <thead>
                <tr>
                  <th>Datum</th>
                  <th>Spiele</th>
                  <th style="color: #4ecca3">Weiß Siege</th>
                  <th style="color: #ff6b6b">Schwarz Siege</th>
                  <th style="color: #f0a500">Remis</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="day in data.gamesOverTime" :key="day.date">
                  <td>{{ day.date }}</td>
                  <td><strong>{{ day.gamesPlayed }}</strong></td>
                  <td>{{ day.whiteWins }}</td>
                  <td>{{ day.blackWins }}</td>
                  <td>{{ day.draws }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.analytics-container {
  padding: 2rem;
  max-width: 1200px;
  margin: 0 auto;
  overflow-y: auto;
  height: 100%;
}

.analytics-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 2rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  padding-bottom: 1rem;
}

.title-section h2 {
  margin: 0;
  color: #4ecca3;
  font-size: 1.8rem;
  font-family: 'Playfair Display', serif;
}

.subtitle {
  margin: 5px 0 0 0;
  font-size: 0.9rem;
  color: rgba(255, 255, 255, 0.6);
}

.refresh-btn {
  background: rgba(78, 204, 163, 0.2);
  border: 1px solid rgba(78, 204, 163, 0.4);
  color: #4ecca3;
  padding: 10px 20px;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: all 0.2s;
}

.refresh-btn:hover:not(:disabled) {
  background: rgba(78, 204, 163, 0.3);
  transform: translateY(-1px);
}

.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* Glassmorphism card */
.card {
  padding: 1.5rem;
  border-radius: 16px;
  margin-bottom: 1.5rem;
}

h3 {
  margin-top: 0;
  margin-bottom: 1.25rem;
  font-size: 1.2rem;
  color: rgba(255, 255, 255, 0.9);
  border-left: 3px solid #4ecca3;
  padding-left: 10px;
}

/* Grids & Rows */
.row-1 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
}

.row-3 {
  display: grid;
  grid-template-columns: 1.2fr 1.8fr;
  gap: 1.5rem;
}

/* Loading, Error, Empty states */
.loading-state, .error-state, .empty-state {
  padding: 3rem;
  text-align: center;
  border-radius: 16px;
  margin-top: 2rem;
}

.loader {
  border: 4px solid rgba(255, 255, 255, 0.1);
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border-left-color: #4ecca3;
  animation: spin 1s linear infinite;
  margin: 0 auto 1.5rem;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.guide-list {
  text-align: left;
  max-width: 500px;
  margin: 1.5rem auto;
  line-height: 1.6;
}

.spark-cmd-info {
  background: rgba(0, 0, 0, 0.3);
  padding: 10px;
  border-radius: 6px;
  font-family: monospace;
  max-width: 600px;
  margin: 1.5rem auto;
  color: #f0a500;
}

/* Game Results Visualization */
.results-visual {
  height: 24px;
  border-radius: 12px;
  display: flex;
  overflow: hidden;
  margin-bottom: 1.5rem;
  background: rgba(255, 255, 255, 0.05);
}

.result-segment {
  height: 100%;
  transition: width 0.5s ease-in-out;
}

.results-legend {
  display: flex;
  justify-content: space-around;
  flex-wrap: wrap;
  gap: 10px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.9rem;
}

.color-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  display: inline-block;
}

.legend-val {
  font-weight: bold;
}

/* Openings Bar list */
.openings-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.opening-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.opening-rank {
  color: rgba(255, 255, 255, 0.4);
  font-weight: bold;
  width: 30px;
}

.opening-name {
  width: 80px;
  font-weight: 600;
}

.bar-container {
  flex-grow: 1;
  height: 10px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 5px;
  overflow: hidden;
}

.bar {
  height: 100%;
  background: linear-gradient(90deg, #4ecca3, #3bb38f);
  border-radius: 5px;
}

.opening-count {
  width: 50px;
  text-align: right;
  font-weight: bold;
}

/* Difficulty Grid */
.difficulty-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 1.25rem;
}

.diff-box {
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  transition: transform 0.2s, background 0.2s;
}

.diff-box:hover {
  transform: translateY(-2px);
  background: rgba(255, 255, 255, 0.05);
}

.diff-title {
  font-weight: bold;
  font-size: 1.1rem;
  color: #4ecca3;
  margin-bottom: 5px;
}

.diff-count {
  font-size: 0.85rem;
  color: rgba(255, 255, 255, 0.5);
  margin-bottom: 15px;
}

.diff-details {
  font-size: 0.85rem;
  width: 100%;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
  padding-top: 10px;
}

.diff-details div {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}

/* Themes Table/Row style */
.themes-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.theme-row {
  display: flex;
  align-items: center;
  font-size: 0.85rem;
}

.theme-name {
  width: 100px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.theme-bar-wrap {
  flex-grow: 1;
  height: 8px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 4px;
  margin: 0 10px;
  overflow: hidden;
}

.theme-bar {
  height: 100%;
  background: #f0a500;
  border-radius: 4px;
}

.theme-count {
  width: 60px;
  font-weight: bold;
  text-align: right;
}

.theme-rating {
  width: 70px;
  color: rgba(255, 255, 255, 0.5);
  text-align: right;
}

/* Games Played Time Table */
.time-list-wrapper {
  max-height: 300px;
  overflow-y: auto;
}

.time-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
}

.time-table th {
  text-align: left;
  padding: 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.6);
  font-weight: 600;
}

.time-table td {
  padding: 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.time-table tr:hover td {
  background: rgba(255, 255, 255, 0.02);
}

.btn {
  padding: 8px 20px;
  border-radius: 8px;
  border: none;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn.primary {
  background: #4ecca3;
  color: #1a1a2e;
}

.btn.primary:hover {
  background: #3bb38f;
}
</style>
