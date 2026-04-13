<script setup>
const props = defineProps(['state'])
</script>

<template>
  <div class="status-panel glass-panel">
    <div class="status-header">
      <div class="status-item">
        <span class="label">PLAYER TO MOVE</span>
        <span class="value" :class="state.activeColor.toLowerCase()">
          {{ state.activeColor }}
        </span>
      </div>
      
      <div class="status-item align-right">
        <span class="label">GAME STATUS</span>
        <span class="value accent">{{ state.status }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.status-panel {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  padding: 1.5rem;
  text-align: left;
}

.status-header {
  display: flex;
  justify-content: space-between;
}

.status-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.align-right {
  text-align: right;
}

.label {
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 0.15rem;
  color: #718096;
  text-transform: uppercase;
}

.value {
  font-size: 1.1rem;
  font-weight: 600;
}

.value.white {
  color: #fff;
  text-shadow: 0 0 10px rgba(255,255,255,0.2);
}

.value.black {
  color: #a0aec0;
}

.accent {
  color: #6366f1;
}
</style>
