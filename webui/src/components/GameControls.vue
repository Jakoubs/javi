<script setup>
const props = defineProps(['state'])
const emit = defineEmits(['command', 'save-game', 'load-game'])

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
          <span class="icon">🤖</span>
          White AI
        </button>
        <button 
          @click="emit('command', 'ai black')" 
          class="cmd-btn ai-btn"
          :class="{ active: state?.aiBlack }"
        >
          <span class="icon">🤖</span>
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
  display: flex;
  align-items: center;
  gap: 0.5rem;
  transition: all 0.3s ease;
}

.ai-btn.active {
  background: rgba(16, 185, 129, 0.2) !important;
  border: 1px solid rgba(52, 211, 153, 0.8) !important;
  color: #fff !important;
  box-shadow: 0 0 15px rgba(16, 185, 129, 0.5), 
              0 0 30px rgba(16, 185, 129, 0.2),
              inset 0 0 10px rgba(16, 185, 129, 0.3) !important;
  text-shadow: 0 0 8px rgba(255, 255, 255, 0.8);
  animation: pulse-glow-green 1.5s infinite cubic-bezier(0.4, 0, 0.2, 1);
}

.ai-btn.active .icon {
  filter: drop-shadow(0 0 5px #10b981);
}

@keyframes pulse-glow-green {
  0% {
    box-shadow: 0 0 10px rgba(16, 185, 129, 0.4), 0 0 20px rgba(16, 185, 129, 0.1);
    transform: scale(1);
  }
  50% {
    box-shadow: 0 0 25px rgba(16, 185, 129, 0.7), 0 0 50px rgba(16, 185, 129, 0.3);
    transform: scale(1.02);
  }
  100% {
    box-shadow: 0 0 10px rgba(16, 185, 129, 0.4), 0 0 20px rgba(16, 185, 129, 0.1);
    transform: scale(1);
  }
}

.icon {
  font-size: 1rem;
  width: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ai-controls, .persistence-controls {
  margin-top: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
</style>
