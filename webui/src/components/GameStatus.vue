<script setup>
import { ref } from 'vue'

const props = defineProps(['state'])
const copied = ref(false)

const copyToClipboard = async () => {
  try {
    await navigator.clipboard.writeText(props.state.fen)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch (err) {
    console.error('Failed to copy FEN:', err)
  }
}
</script>

<template>
  <div class="status-panel glass-panel">
    <div class="status-item">
      <span class="label">PLAYER TO MOVE</span>
      <span class="value" :class="state.activeColor.toLowerCase()">
        {{ state.activeColor }}
      </span>
    </div>
    
    <div class="status-item">
      <span class="label">GAME STATUS</span>
      <span class="value accent">{{ state.status }}</span>
    </div>

    <div class="fen-container">
      <div class="fen-header">
        <span class="label">FEN STRING</span>
        <button class="copy-btn" @click="copyToClipboard" :title="copied ? 'Copied!' : 'Copy FEN'">
          <svg v-if="!copied" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
          <svg v-else xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="success-icon"><polyline points="20 6 9 17 4 12"></polyline></svg>
        </button>
      </div>
      <div class="fen-box">{{ state.fen }}</div>
    </div>
  </div>
</template>

<style scoped>
.status-panel {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
  padding: 1.5rem;
  text-align: left;
}

.status-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.label {
  font-size: 0.7rem;
  font-weight: 600;
  letter-spacing: 0.15rem;
  color: #718096;
}

.value {
  font-size: 1.2rem;
  font-weight: 600;
}

.value.white {
  color: #fff;
  text-shadow: 0 0 10px rgba(255,255,255,0.3);
}

.value.black {
  color: #a0aec0;
}

.accent {
  color: #6366f1;
}

.fen-container {
  margin-top: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.fen-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.copy-btn {
  background: transparent;
  border: none;
  color: #718096;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
}

.copy-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.success-icon {
  color: #10b981;
}

.fen-box {
  background: rgba(0, 0, 0, 0.2);
  padding: 0.75rem;
  border-radius: 8px;
  font-family: monospace;
  font-size: 0.75rem;
  word-break: break-all;
  color: #a0aec0;
  border: 1px solid rgba(255, 255, 255, 0.05);
}
</style>
