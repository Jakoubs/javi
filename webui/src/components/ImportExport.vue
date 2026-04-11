<script setup>
import { ref } from 'vue'

const props = defineProps({
  fen: String,
  pgn: String
})

const emit = defineEmits(['loadFen', 'loadPgn'])

const activeTab = ref('fen')
const fenInput = ref('')
const pgnInput = ref('')

const handleLoadFen = () => {
  if (fenInput.value.trim()) {
    emit('loadFen', fenInput.value.trim())
    fenInput.value = ''
  }
}

const handleLoadPgn = () => {
  if (pgnInput.value.trim()) {
    emit('loadPgn', pgnInput.value.trim())
    pgnInput.value = ''
  }
}

const copyToClipboard = (text) => {
  navigator.clipboard.writeText(text)
}
</script>

<template>
  <div class="menu-card glass">
    <div class="tabs">
      <button 
        :class="['tab-btn', { active: activeTab === 'fen' }]" 
        @click="activeTab = 'fen'"
      >
        FEN
      </button>
      <button 
        :class="['tab-btn', { active: activeTab === 'pgn' }]" 
        @click="activeTab = 'pgn'"
      >
        PGN
      </button>
    </div>

    <div v-if="activeTab === 'fen'" class="tab-content">
      <div class="field">
        <label>Current FEN</label>
        <div class="copy-box">
          <code>{{ fen }}</code>
          <button class="icon-btn" @click="copyToClipboard(fen)" title="Copy">
            📋
          </button>
        </div>
      </div>
      <div class="field">
        <label>Import FEN</label>
        <div class="input-group">
          <input 
            v-model="fenInput" 
            placeholder="Paste FEN string..."
            class="glass-input"
          />
          <button class="primary-btn" @click="handleLoadFen">Load</button>
        </div>
      </div>
    </div>

    <div v-if="activeTab === 'pgn'" class="tab-content">
      <div class="field">
        <label>Current PGN</label>
        <div class="copy-box pgn-box">
          <pre>{{ pgn || 'No moves yet' }}</pre>
          <button class="icon-btn" @click="copyToClipboard(pgn)" title="Copy">
            📋
          </button>
        </div>
      </div>
      <div class="field">
        <label>Import PGN</label>
        <textarea 
          v-model="pgnInput" 
          placeholder="Paste PGN history..."
          class="glass-input pgn-input"
        ></textarea>
        <button class="primary-btn wide" @click="handleLoadPgn">Load Game</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.menu-card {
  border-radius: 12px;
  padding: 1rem;
  margin-bottom: 1rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.tabs {
  display: flex;
  background: rgba(0,0,0,0.2);
  border-radius: 8px;
  padding: 4px;
}

.tab-btn {
  flex: 1;
  background: transparent;
  border: none;
  color: #ccc;
  padding: 6px;
  cursor: pointer;
  border-radius: 6px;
  transition: all 0.3s;
}

.tab-btn.active {
  background: rgba(255,255,255,0.1);
  color: white;
  box-shadow: 0 2px 4px rgba(0,0,0,0.2);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

label {
  font-size: 0.8rem;
  color: #aaa;
  font-weight: 600;
  text-transform: uppercase;
}

.copy-box {
  background: rgba(0,0,0,0.3);
  padding: 0.5rem;
  border-radius: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-family: monospace;
  font-size: 0.75rem;
  overflow: hidden;
}

.copy-box code {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-right: 8px;
}

.pgn-box {
  max-height: 100px;
  overflow-y: auto;
  align-items: flex-start;
}

.pgn-box pre {
  margin: 0;
  white-space: pre-wrap;
  flex: 1;
}

.input-group {
  display: flex;
  gap: 0.5rem;
}

.glass-input {
  flex: 1;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 6px;
  padding: 0.5rem;
  color: white;
  font-size: 0.9rem;
}

.pgn-input {
  min-height: 60px;
  resize: vertical;
}

.primary-btn {
  background: #27ae60;
  color: white;
  border: none;
  padding: 0.5rem 1rem;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 600;
}

.primary-btn.wide {
  width: 100%;
}

.icon-btn {
  background: transparent;
  border: none;
  cursor: pointer;
  opacity: 0.7;
}

.icon-btn:hover {
  opacity: 1;
}
</style>
