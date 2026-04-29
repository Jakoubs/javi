<template>
  <div class="admin-overlay" @click.self="$emit('close')">
    <div class="admin-panel">
      <!-- Header -->
      <div class="admin-header">
        <div class="admin-title">
          <span class="admin-icon">⚙️</span>
          <h2>Admin Panel</h2>
        </div>
        <button class="close-btn" @click="$emit('close')">✕</button>
      </div>

      <!-- Tabs -->
      <div class="tab-bar">
        <button v-for="t in tabs" :key="t.id" :class="['tab-btn', { active: activeTab === t.id }]" @click="activeTab = t.id">
          {{ t.icon }} {{ t.label }}
        </button>
      </div>

      <!-- ── STATISTIKEN ── -->
      <div v-if="activeTab === 'stats'" class="tab-content">
        <div v-if="statsLoading" class="loading">Lade Statistiken…</div>
        <div v-else class="stats-grid">
          <div class="stat-card">
            <div class="stat-value">{{ stats.totalUsers }}</div>
            <div class="stat-label">Nutzer gesamt</div>
          </div>
          <div class="stat-card verified">
            <div class="stat-value">{{ stats.verifiedUsers }}</div>
            <div class="stat-label">Verifiziert</div>
          </div>
          <div class="stat-card banned">
            <div class="stat-value">{{ stats.bannedUsers }}</div>
            <div class="stat-label">Gesperrt</div>
          </div>
          <div class="stat-card active">
            <div class="stat-value">{{ stats.activeGames }}</div>
            <div class="stat-label">Aktive Spiele</div>
          </div>
          <div class="stat-card moves">
            <div class="stat-value">{{ stats.totalMovesPlayed }}</div>
            <div class="stat-label">Züge gespielt</div>
          </div>
        </div>
        <div v-if="statsError" class="error-msg">{{ statsError }}</div>
      </div>

      <!-- ── NUTZERVERWALTUNG ── -->
      <div v-if="activeTab === 'users'" class="tab-content">
        <div class="toolbar">
          <input v-model="userSearch" class="search-input" placeholder="🔍 Nutzer suchen…" />
          <button class="btn-refresh" @click="loadUsers">↻ Aktualisieren</button>
        </div>
        <div v-if="usersLoading" class="loading">Lade Nutzer…</div>
        <div v-else-if="usersError" class="error-msg">{{ usersError }}</div>
        <div v-else class="table-wrap">
          <table class="user-table">
            <thead>
              <tr>
                <th>ID</th><th>Username</th><th>Email</th><th>Status</th><th>Erstellt</th><th>Aktionen</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="u in filteredUsers" :key="u.id">
                <td class="id-col">{{ u.id }}</td>
                <td>{{ u.username }}</td>
                <td class="email-col">{{ u.email }}</td>
                <td>
                  <span :class="['badge', u.isBanned ? 'badge-banned' : u.isVerified ? 'badge-ok' : 'badge-pending']">
                    {{ u.isBanned ? '🔒 Gesperrt' : u.isVerified ? '✅ Verifiziert' : '⏳ Ausstehend' }}
                  </span>
                </td>
                <td class="date-col">{{ u.createdAt.split('T')[0] }}</td>
                <td class="actions-col">
                  <button class="act-btn edit" title="Bearbeiten" @click="openEdit(u)">✏️</button>
                  <button class="act-btn" :title="u.isVerified ? 'Deverifizieren' : 'Verifizieren'" @click="toggleVerify(u)">
                    {{ u.isVerified ? '❌' : '✅' }}
                  </button>
                  <button class="act-btn" :title="u.isBanned ? 'Entsperren' : 'Sperren'" @click="toggleBan(u)">
                    {{ u.isBanned ? '🔓' : '🔒' }}
                  </button>
                  <button class="act-btn delete" title="Löschen" @click="confirmDelete(u)">🗑️</button>
                </td>
              </tr>
              <tr v-if="filteredUsers.length === 0">
                <td colspan="6" class="empty-row">Keine Nutzer gefunden.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- ── AKTIVE SPIELE ── -->
      <div v-if="activeTab === 'games'" class="tab-content">
        <div class="toolbar">
          <button class="btn-refresh" @click="loadGames">↻ Aktualisieren</button>
        </div>
        <div v-if="gamesLoading" class="loading">Lade Spiele…</div>
        <div v-else-if="gamesError" class="error-msg">{{ gamesError }}</div>
        <div v-else-if="games.length === 0" class="empty-state">Keine aktiven Spiele.</div>
        <div v-else class="games-list">
          <div v-for="g in games" :key="g.gameId" class="game-card">
            <div class="game-info">
              <span class="game-id">#{{ g.gameId }}</span>
              <span :class="['badge', 'badge-ok']">{{ g.status }}</span>
              <span class="game-meta">{{ g.activeColor }} am Zug · {{ g.moveCount }} Züge</span>
            </div>
            <div class="game-fen">{{ g.fen }}</div>
            <a :href="g.spectateUrl" target="_blank" class="spectate-btn">👁 Spectate</a>
          </div>
        </div>
      </div>
    </div>

    <!-- Edit Modal -->
    <div v-if="editUser" class="modal-overlay" @click.self="editUser = null">
      <div class="edit-modal glass">
        <h3>Nutzer bearbeiten</h3>
        <label>Username</label>
        <input v-model="editForm.username" class="glass-input" />
        <label>Email</label>
        <input v-model="editForm.email" class="glass-input" />
        <div class="modal-actions">
          <button class="btn primary" @click="saveEdit">Speichern</button>
          <button class="btn" @click="editUser = null">Abbrechen</button>
        </div>
      </div>
    </div>

    <!-- Delete Confirm -->
    <div v-if="deleteTarget" class="modal-overlay" @click.self="deleteTarget = null">
      <div class="edit-modal glass">
        <h3>Nutzer löschen?</h3>
        <p>Möchtest du <strong>{{ deleteTarget.username }}</strong> wirklich unwiderruflich löschen?</p>
        <div class="modal-actions">
          <button class="btn danger" @click="doDelete">Löschen</button>
          <button class="btn" @click="deleteTarget = null">Abbrechen</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'

const props = defineProps({ token: String })
const emit = defineEmits(['close'])

const API = 'http://localhost:8080/api/admin'
const headers = computed(() => ({
  'Content-Type': 'application/json',
  'Authorization': `Bearer ${props.token}`
}))

const tabs = [
  { id: 'stats', icon: '📊', label: 'Statistiken' },
  { id: 'users', icon: '👥', label: 'Nutzerverwaltung' },
  { id: 'games', icon: '🎮', label: 'Aktive Spiele' }
]
const activeTab = ref('stats')

// ── Stats ──────────────────────────────────────────────────────────────────
const stats = ref({ totalUsers: 0, verifiedUsers: 0, bannedUsers: 0, activeGames: 0, totalMovesPlayed: 0 })
const statsLoading = ref(false)
const statsError = ref('')

async function loadStats() {
  statsLoading.value = true; statsError.value = ''
  try {
    const r = await fetch(`${API}/stats`, { headers: headers.value })
    if (!r.ok) throw new Error(await r.text())
    stats.value = await r.json()
  } catch (e) { statsError.value = e.message } finally { statsLoading.value = false }
}

// ── Users ──────────────────────────────────────────────────────────────────
const users = ref([])
const userSearch = ref('')
const usersLoading = ref(false)
const usersError = ref('')
const editUser = ref(null)
const editForm = ref({ username: '', email: '' })
const deleteTarget = ref(null)

const filteredUsers = computed(() =>
  users.value.filter(u =>
    u.username.toLowerCase().includes(userSearch.value.toLowerCase()) ||
    u.email.toLowerCase().includes(userSearch.value.toLowerCase())
  )
)

async function loadUsers() {
  usersLoading.value = true; usersError.value = ''
  try {
    const r = await fetch(`${API}/users`, { headers: headers.value })
    if (!r.ok) throw new Error(await r.text())
    users.value = await r.json()
  } catch (e) { usersError.value = e.message } finally { usersLoading.value = false }
}

function openEdit(u) { editUser.value = u; editForm.value = { username: u.username, email: u.email } }

async function saveEdit() {
  const r = await fetch(`${API}/users/${editUser.value.id}`, {
    method: 'PUT', headers: headers.value,
    body: JSON.stringify(editForm.value)
  })
  if (r.ok) { const updated = await r.json(); Object.assign(editUser.value, updated); editUser.value = null }
}

async function toggleVerify(u) {
  const r = await fetch(`${API}/users/${u.id}/verify?verified=${!u.isVerified}`, { method: 'POST', headers: headers.value })
  if (r.ok) Object.assign(u, await r.json())
}

async function toggleBan(u) {
  const r = await fetch(`${API}/users/${u.id}/ban?banned=${!u.isBanned}`, { method: 'POST', headers: headers.value })
  if (r.ok) Object.assign(u, await r.json())
}

function confirmDelete(u) { deleteTarget.value = u }

async function doDelete() {
  const r = await fetch(`${API}/users/${deleteTarget.value.id}`, { method: 'DELETE', headers: headers.value })
  if (r.ok) { users.value = users.value.filter(u => u.id !== deleteTarget.value.id); deleteTarget.value = null }
}

// ── Games ──────────────────────────────────────────────────────────────────
const games = ref([])
const gamesLoading = ref(false)
const gamesError = ref('')

async function loadGames() {
  gamesLoading.value = true; gamesError.value = ''
  try {
    const r = await fetch(`${API}/games`, { headers: headers.value })
    if (!r.ok) throw new Error(await r.text())
    games.value = await r.json()
  } catch (e) { gamesError.value = e.message } finally { gamesLoading.value = false }
}

watch(activeTab, t => {
  if (t === 'stats') loadStats()
  else if (t === 'users') loadUsers()
  else if (t === 'games') loadGames()
})

onMounted(() => loadStats())
</script>

<style scoped>
.admin-overlay {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.7);
  backdrop-filter: blur(6px);
  display: flex; align-items: center; justify-content: center;
  z-index: 2000;
}

.admin-panel {
  width: min(96vw, 900px);
  max-height: 88vh;
  background: rgba(20, 20, 40, 0.96);
  border: 1px solid rgba(78,204,163,0.25);
  border-radius: 20px;
  box-shadow: 0 24px 64px rgba(0,0,0,0.6), 0 0 0 1px rgba(78,204,163,0.1);
  display: flex; flex-direction: column;
  overflow: hidden;
  animation: panel-in 0.3s ease-out;
}

@keyframes panel-in {
  from { opacity:0; transform: scale(0.95) translateY(20px); }
  to   { opacity:1; transform: scale(1) translateY(0); }
}

.admin-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}

.admin-title { display: flex; align-items: center; gap: 0.75rem; }
.admin-title h2 { margin: 0; font-size: 1.3rem; color: #4ecca3; letter-spacing: 1px; }
.admin-icon { font-size: 1.4rem; }

.close-btn {
  background: none; border: none; color: rgba(255,255,255,0.5);
  font-size: 1.4rem; cursor: pointer; transition: color 0.2s;
  padding: 4px 8px; border-radius: 6px;
}
.close-btn:hover { color: #ff6b6b; background: rgba(255,107,107,0.1); }

.tab-bar {
  display: flex; gap: 4px; padding: 0.75rem 1.5rem;
  border-bottom: 1px solid rgba(255,255,255,0.06);
}

.tab-btn {
  padding: 7px 18px; border-radius: 8px; border: 1px solid transparent;
  background: none; color: rgba(255,255,255,0.5);
  font-size: 0.88rem; font-weight: 600; cursor: pointer; transition: all 0.2s;
}
.tab-btn:hover { color: white; background: rgba(255,255,255,0.07); }
.tab-btn.active {
  color: #4ecca3;
  background: rgba(78,204,163,0.12);
  border-color: rgba(78,204,163,0.3);
}

.tab-content {
  flex: 1; overflow-y: auto; padding: 1.25rem 1.5rem;
}

/* Stats */
.stats-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 1rem;
}

.stat-card {
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 14px; padding: 1.25rem 1rem; text-align: center;
  transition: transform 0.2s;
}
.stat-card:hover { transform: translateY(-3px); }
.stat-value { font-size: 2.4rem; font-weight: 800; color: #4ecca3; }
.stat-label { font-size: 0.8rem; color: rgba(255,255,255,0.5); margin-top: 4px; text-transform: uppercase; letter-spacing: 0.5px; }
.stat-card.verified .stat-value { color: #4ecca3; }
.stat-card.banned .stat-value { color: #ff6b6b; }
.stat-card.active .stat-value { color: #f0a500; }
.stat-card.moves .stat-value { color: #a29bfe; }

/* Toolbar */
.toolbar {
  display: flex; gap: 0.75rem; margin-bottom: 1rem; align-items: center;
}
.search-input {
  flex: 1; background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1);
  border-radius: 8px; color: white; padding: 8px 14px; font-size: 0.9rem; outline: none;
}
.search-input:focus { border-color: #4ecca3; }
.btn-refresh {
  background: rgba(78,204,163,0.15); border: 1px solid rgba(78,204,163,0.3);
  color: #4ecca3; padding: 8px 14px; border-radius: 8px; cursor: pointer;
  font-size: 0.88rem; transition: all 0.2s;
}
.btn-refresh:hover { background: rgba(78,204,163,0.25); }

/* Table */
.table-wrap { overflow-x: auto; }

.user-table {
  width: 100%; border-collapse: collapse; font-size: 0.88rem;
}
.user-table th {
  text-align: left; padding: 10px 12px;
  color: rgba(255,255,255,0.4); font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.5px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.user-table td {
  padding: 10px 12px; border-bottom: 1px solid rgba(255,255,255,0.04);
  vertical-align: middle;
}
.user-table tr:hover td { background: rgba(255,255,255,0.03); }
.id-col { color: rgba(255,255,255,0.35); font-family: monospace; }
.email-col { color: rgba(255,255,255,0.6); font-size: 0.83rem; }
.date-col { color: rgba(255,255,255,0.4); font-size: 0.8rem; }
.empty-row { text-align: center; color: rgba(255,255,255,0.3); padding: 2rem; }

.badge {
  padding: 3px 10px; border-radius: 20px; font-size: 0.78rem; font-weight: 600; white-space: nowrap;
}
.badge-ok     { background: rgba(78,204,163,0.15); color: #4ecca3; border: 1px solid rgba(78,204,163,0.3); }
.badge-banned { background: rgba(255,107,107,0.15); color: #ff6b6b; border: 1px solid rgba(255,107,107,0.3); }
.badge-pending{ background: rgba(240,165,0,0.15); color: #f0a500; border: 1px solid rgba(240,165,0,0.3); }

.actions-col { white-space: nowrap; }
.act-btn {
  background: rgba(255,255,255,0.07); border: 1px solid rgba(255,255,255,0.1);
  border-radius: 6px; padding: 4px 8px; cursor: pointer; font-size: 1rem;
  margin-right: 4px; transition: all 0.15s;
}
.act-btn:hover { background: rgba(255,255,255,0.15); transform: scale(1.1); }
.act-btn.delete:hover { background: rgba(255,107,107,0.2); border-color: #ff6b6b; }

/* Games */
.games-list { display: flex; flex-direction: column; gap: 1rem; }
.game-card {
  background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08);
  border-radius: 12px; padding: 1rem 1.25rem;
  display: flex; flex-direction: column; gap: 0.5rem;
}
.game-info { display: flex; align-items: center; gap: 0.75rem; }
.game-id { font-family: monospace; color: rgba(255,255,255,0.4); }
.game-meta { color: rgba(255,255,255,0.5); font-size: 0.85rem; }
.game-fen { font-family: monospace; font-size: 0.75rem; color: rgba(255,255,255,0.3); word-break: break-all; }
.spectate-btn {
  display: inline-block; margin-top: 4px;
  background: rgba(78,204,163,0.12); border: 1px solid rgba(78,204,163,0.3);
  color: #4ecca3; padding: 6px 16px; border-radius: 8px;
  text-decoration: none; font-size: 0.85rem; font-weight: 600;
  transition: all 0.2s; align-self: flex-start;
}
.spectate-btn:hover { background: rgba(78,204,163,0.25); }

/* Misc */
.loading { color: rgba(255,255,255,0.4); text-align: center; padding: 2rem; }
.error-msg { color: #ff6b6b; background: rgba(255,107,107,0.1); border-radius: 8px; padding: 0.75rem 1rem; margin-top: 1rem; }
.empty-state { color: rgba(255,255,255,0.3); text-align: center; padding: 3rem; font-size: 1rem; }

/* Edit/Delete modal */
.modal-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.6);
  display: flex; align-items: center; justify-content: center; z-index: 3000;
}
.edit-modal {
  background: rgba(20,20,40,0.97); border: 1px solid rgba(78,204,163,0.25);
  border-radius: 16px; padding: 2rem; min-width: 340px;
  display: flex; flex-direction: column; gap: 0.75rem;
}
.edit-modal h3 { margin: 0 0 0.5rem; color: #4ecca3; }
.edit-modal label { font-size: 0.8rem; color: rgba(255,255,255,0.5); text-transform: uppercase; }
.glass-input {
  background: rgba(255,255,255,0.07); border: 1px solid rgba(255,255,255,0.12);
  border-radius: 8px; color: white; padding: 9px 12px; font-size: 0.95rem; outline: none; width: 100%; box-sizing: border-box;
}
.glass-input:focus { border-color: #4ecca3; }
.modal-actions { display: flex; gap: 0.75rem; margin-top: 0.5rem; }
.btn {
  padding: 9px 20px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.15);
  background: rgba(255,255,255,0.07); color: white; font-weight: 600; cursor: pointer; transition: all 0.2s;
}
.btn:hover { background: rgba(255,255,255,0.14); }
.btn.primary { background: #4ecca3; border-color: #4ecca3; color: #1a1a2e; }
.btn.primary:hover { background: #3eb88f; }
.btn.danger  { background: rgba(255,107,107,0.2); border-color: #ff6b6b; color: #ff6b6b; }
.btn.danger:hover { background: rgba(255,107,107,0.35); }
</style>
