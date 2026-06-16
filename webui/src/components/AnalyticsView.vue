<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'

const props = defineProps({
  serverUrl: {
    type: String,
    required: true
  },
  currentUser: {
    type: Object,
    default: null
  }
})

const loading = ref(true)
const errorMsg = ref(null)
const selectedUser = ref('')
const refreshInterval = ref(null)

const data = ref({
  gameResults: [],
  popularFirstMoves: [],
  gamesOverTime: [],
  userWinRates: [],
  botStats: []
})


const fetchAnalytics = async (silent = false) => {
  if (!silent) loading.value = true
  errorMsg.value = null
  try {
    let url = `${props.serverUrl}/api/analytics/summary`
    if (selectedUser.value) {
      url += `?username=${encodeURIComponent(selectedUser.value)}`
    }
    const response = await fetch(url)
    if (response.ok) {
      data.value = await response.json()
    } else {
      if (!silent) errorMsg.value = 'Fehler beim Laden der Spieldaten vom Server.'
    }
  } catch (e) {
    console.error(e)
    if (!silent) errorMsg.value = 'Server konnte nicht erreicht werden.'
  } finally {
    if (!silent) loading.value = false
  }
}



onMounted(() => {
  if (props.currentUser) {
    selectedUser.value = props.currentUser.username
  } else {
    selectedUser.value = ''
  }
  fetchAnalytics()
  // Auto-refresh stats silently every 4 seconds for instant updates
  refreshInterval.value = setInterval(() => {
    fetchAnalytics(true)
  }, 4000)
})

onUnmounted(() => {
  if (refreshInterval.value) {
    clearInterval(refreshInterval.value)
  }
})

watch(() => props.currentUser, (newVal) => {
  selectedUser.value = newVal ? newVal.username : ''
  fetchAnalytics()
}, { deep: true })

const isDataEmpty = () => {
  return !data.value.gameResults.length &&
         !data.value.popularFirstMoves.length &&
         !data.value.gamesOverTime.length
}

const getWinnerColor = (winner) => {
  const w = winner.toLowerCase()
  if (w.includes('white') || w === 'wins') return '#4ecca3'
  if (w.includes('black') || w === 'losses') return '#ff6b6b'
  if (w.includes('draw') || w === 'draws') return '#f0a500'
  return '#708090'
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

      <div class="controls-section">
        <!-- User Information (No dropdown) -->
        <div class="user-select-wrap">
          <span v-if="props.currentUser" class="user-display">👤 {{ props.currentUser.username }}</span>
          <span v-else class="user-display">🌍 Alle Spieler (Global)</span>
        </div>

        <button @click="fetchAnalytics" class="refresh-btn" :disabled="loading">
          <span v-if="loading" class="spinner"></span>
          <span>🔄 Aktualisieren</span>
        </button>
      </div>
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

    <!-- Empty state -->
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
          <h3>🏆 Spielresultate {{ selectedUser ? `für ${selectedUser}` : '(Global)' }}</h3>
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
              <span class="legend-val">{{ r.count }} ({{ formatPercent(r.percentage)}}%)</span>
            </div>
          </div>
        </div>

        <!-- Popular Openings -->
        <div class="card glass openings-card">
          <h3>📖 Beliebteste Eröffnungszüge {{ selectedUser ? `von ${selectedUser}` : '' }}</h3>
          <div class="openings-list">
            <div v-for="(op, index) in data.popularFirstMoves" :key="op.san" class="opening-item">
              <span class="opening-rank">#{{ index + 1 }}</span>
              <span class="opening-name">{{ op.san }}</span>
              <div class="bar-container">
                <div 
                  class="bar" 
                  :style="{ width: data.popularFirstMoves.length ? (op.count / data.popularFirstMoves[0].count) * 100 + '%' : '0%' }"
                ></div>
              </div>
              <span class="opening-count">{{ op.count }}x</span>
            </div>
            <div v-if="!data.popularFirstMoves || !data.popularFirstMoves.length" class="no-data">
              Keine Eröffnungszüge aufgezeichnet
            </div>
          </div>
        </div>
      </div>

      <!-- Row 2: Leaderboards (Humans & Bots) -->
      <div class="row-1">
        <!-- Human Leaderboard -->
        <div class="card glass leaderboard-card">
          <h3>🏆 Spieler-Rangliste</h3>
          <div class="table-wrapper">
            <table class="leaderboard-table">
              <thead>
                <tr>
                  <th>Rang</th>
                  <th>Spieler</th>
                  <th>Spiele</th>
                  <th style="color: #4ecca3">Siege</th>
                  <th style="color: #ff6b6b">Ndl.</th>
                  <th style="color: #f0a500">Remis</th>
                  <th>Quote</th>
                </tr>
              </thead>
              <tbody>
                <tr 
                  v-for="(user, index) in data.userWinRates" 
                  :key="user.username" 
                  class="leaderboard-row"
                  :class="{ 'current-filter': user.username === selectedUser }"
                >
                  <td><span class="rank-badge" :class="'rank-' + (index + 1)">{{ index + 1 }}</span></td>
                  <td class="player-name"><strong>{{ user.username }}</strong></td>
                  <td>{{ user.gamesPlayed }}</td>
                  <td>{{ user.wins }}</td>
                  <td>{{ user.losses }}</td>
                  <td>{{ user.draws }}</td>
                  <td>
                    <div class="rate-cell">
                      <span class="rate-text">{{ user.winRate.toFixed(1) }}%</span>
                      <div class="mini-bar-wrap">
                        <div class="mini-bar" :style="{ width: user.winRate + '%', backgroundColor: '#4ecca3' }"></div>
                      </div>
                    </div>
                  </td>
                </tr>
                <tr v-if="!data.userWinRates || !data.userWinRates.length">
                  <td colspan="7" class="no-data">Keine Ranglistendaten vorhanden</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Bot Leaderboard -->
        <div class="card glass bot-stats-card">
          <h3>🤖 Bot-Erfolgsrate</h3>
          <div class="table-wrapper">
            <table class="leaderboard-table">
              <thead>
                <tr>
                  <th>Bot</th>
                  <th>Spiele</th>
                  <th style="color: #4ecca3">Siege</th>
                  <th style="color: #ff6b6b">Ndl.</th>
                  <th style="color: #f0a500">Remis</th>
                  <th>Quote</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="bot in data.botStats" :key="bot.botName" class="leaderboard-row">
                  <td class="bot-name">🤖 <strong>{{ bot.botName.replace('bot:', '') }}</strong></td>
                  <td>{{ bot.gamesPlayed }}</td>
                  <td>{{ bot.wins }}</td>
                  <td>{{ bot.losses }}</td>
                  <td>{{ bot.draws }}</td>
                  <td>
                    <div class="rate-cell">
                      <span class="rate-text">{{ bot.winRate.toFixed(1) }}%</span>
                      <div class="mini-bar-wrap">
                        <div class="mini-bar" :style="{ width: bot.winRate + '%', backgroundColor: '#3bb38f' }"></div>
                      </div>
                    </div>
                  </td>
                </tr>
                <tr v-if="!data.botStats || !data.botStats.length">
                  <td colspan="6" class="no-data">Keine Bot-Statistiken vorhanden</td>
                </tr>
              </tbody>
            </table>
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
                <th style="color: #4ecca3">{{ selectedUser ? 'Siege' : 'Weiß Siege' }}</th>
                <th style="color: #ff6b6b">{{ selectedUser ? 'Ndl.' : 'Schwarz Siege' }}</th>
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
              <tr v-if="!data.gamesOverTime || !data.gamesOverTime.length">
                <td colspan="5" class="no-data">Keine Zeitverlaufsdaten vorhanden</td>
              </tr>
            </tbody>
          </table>
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
  padding-bottom: 1.5rem;
  flex-wrap: wrap;
  gap: 1.5rem;
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

.controls-section {
  display: flex;
  align-items: center;
  gap: 1.5rem;
}

.user-select-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-select-wrap label {
  font-size: 0.9rem;
  color: rgba(255, 255, 255, 0.7);
  font-weight: 500;
}

.glass-select {
  background: rgba(25ff, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  color: #fff;
  padding: 8px 16px;
  cursor: pointer;
  outline: none;
  font-weight: 500;
  transition: all 0.2s;
  background-color: #1a1a2e;
}

.glass-select:focus {
  border-color: #4ecca3;
  box-shadow: 0 0 8px rgba(78, 204, 163, 0.3);
}

.refresh-btn {
  background: rgba(78, 204, 163, 0.15);
  border: 1px solid rgba(78, 204, 163, 0.3);
  color: #4ecca3;
  padding: 8px 18px;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: all 0.2s;
}

.refresh-btn:hover:not(:disabled) {
  background: rgba(78, 204, 163, 0.25);
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
  transition: transform 0.2s, box-shadow 0.2s;
}

.card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.3);
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
  margin-bottom: 1.5rem;
}

@media (max-width: 768px) {
  .row-1 {
    grid-template-columns: 1fr !important;
  }
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
  gap: 15px;
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



/* Tables and Leaderboards */
.table-wrapper {
  overflow-x: auto;
}

.leaderboard-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
  text-align: left;
}

.leaderboard-table th {
  padding: 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.6);
  font-weight: 600;
}

.leaderboard-table td {
  padding: 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

.leaderboard-row {
  transition: background 0.2s;
}

.user-display {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  color: #4ecca3;
  padding: 8px 16px;
  font-weight: 600;
  display: inline-block;
  backdrop-filter: blur(10px);
}

.leaderboard-row:hover {
  background: rgba(255, 255, 255, 0.05) !important;
}

.current-filter {
  background: rgba(78, 204, 163, 0.1) !important;
  border-left: 3px solid #4ecca3;
}

.rank-badge {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  font-weight: bold;
  font-size: 0.8rem;
}

.rank-1 { background: gold; color: #1a1a2e; }
.rank-2 { background: silver; color: #1a1a2e; }
.rank-3 { background: #cd7f32; color: #1a1a2e; }

.rate-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.rate-text {
  font-weight: bold;
}

.mini-bar-wrap {
  width: 100px;
  height: 4px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 2px;
  overflow: hidden;
}

.mini-bar {
  height: 100%;
  border-radius: 2px;
}

.no-data {
  text-align: center;
  padding: 2rem;
  color: rgba(255, 255, 255, 0.4);
  font-style: italic;
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
  padding: 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.6);
  font-weight: 600;
}

.time-table td {
  padding: 12px;
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
