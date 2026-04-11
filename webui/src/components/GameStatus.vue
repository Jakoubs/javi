<script setup>
const props = defineProps(['state'])
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
      <span class="label">FEN STRING</span>
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
