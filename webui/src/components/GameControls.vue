<script setup>
const emit = defineEmits(['command'])

const commands = [
  { label: 'Undo Move', icon: '↺', cmd: 'undo' },
  { label: 'New Game', icon: '⊕', cmd: 'new' },
  { label: 'AI Move', icon: '🤖', cmd: 'ai' },
  { label: 'Resign', icon: '🏳', cmd: 'resign' },
  { label: 'Flip Board', icon: '⇅', cmd: 'flip' }
]
</script>

<template>
  <div class="controls glass-panel">
    <h3>COMMANDS</h3>
    <div class="button-grid">
      <button 
        v-for="btn in commands" 
        :key="btn.cmd" 
        @click="emit('command', btn.cmd)"
        class="cmd-btn"
      >
        <span class="icon">{{ btn.icon }}</span>
        {{ btn.label }}
      </button>
    </div>
    
    <div class="ai-controls">
      <h4>AI AUTOMATION</h4>
      <div class="button-grid">
        <button @click="emit('command', 'ai white')" class="cmd-btn">White AI</button>
        <button @click="emit('command', 'ai black')" class="cmd-btn">Black AI</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1.5rem;
  text-align: left;
}

h3, h4 {
  margin: 0;
  font-size: 0.9rem;
  letter-spacing: 0.2rem;
  color: #a0aec0;
  border-bottom: 1px solid rgba(255,255,255,0.1);
  padding-bottom: 0.5rem;
}

.button-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}

.cmd-btn {
  width: 100%;
  justify-content: flex-start;
  font-size: 0.85rem;
  background: rgba(255, 255, 255, 0.03);
}

.icon {
  font-size: 1.1rem;
  width: 20px;
}

.ai-controls {
  margin-top: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
</style>
