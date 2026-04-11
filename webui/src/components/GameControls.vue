<script setup>
const props = defineProps(['state'])
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
        <button 
          @click="emit('command', 'ai white')" 
          class="cmd-btn ai-btn"
          :class="{ active: state?.aiWhite }"
        >
          White AI
        </button>
        <button 
          @click="emit('command', 'ai black')" 
          class="cmd-btn ai-btn"
          :class="{ active: state?.aiBlack }"
        >
          Black AI
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 1rem;
  text-align: left;
}

h3, h4 {
  margin: 0;
  font-size: 0.8rem;
  letter-spacing: 0.15rem;
  color: #a0aec0;
  border-bottom: 1px solid rgba(255,255,255,0.1);
  padding-bottom: 0.25rem;
}

.button-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
}

.cmd-btn {
  width: 100%;
  justify-content: flex-start;
  font-size: 0.8rem;
  padding: 0.5rem 0.75rem;
  background: rgba(255, 255, 255, 0.03);
  position: relative;
  overflow: hidden;
}

.ai-btn.active {
  background: rgba(99, 102, 241, 0.2);
  border: 1px solid rgba(99, 102, 241, 0.5);
  color: #fff;
  box-shadow: 0 0 15px rgba(99, 102, 241, 0.4);
  animation: pulse-glow 2s infinite;
}

@keyframes pulse-glow {
  0% {
    box-shadow: 0 0 5px rgba(99, 102, 241, 0.2);
    border-color: rgba(99, 102, 241, 0.3);
  }
  50% {
    box-shadow: 0 0 20px rgba(99, 102, 241, 0.6);
    border-color: rgba(99, 102, 241, 0.8);
  }
  100% {
    box-shadow: 0 0 5px rgba(99, 102, 241, 0.2);
    border-color: rgba(99, 102, 241, 0.3);
  }
}

.icon {
  font-size: 1rem;
  width: 18px;
}

.ai-controls {
  margin-top: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
</style>
