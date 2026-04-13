<script setup>
import { computed, nextTick, watch, ref } from 'vue'

const props = defineProps(['moves', 'viewIndex'])
const emit = defineEmits(['jump', 'command'])

const scrollContainer = ref(null)

const groupedMoves = computed(() => {
  const groups = []
  for (let i = 0; i < props.moves.length; i += 2) {
    groups.push({
      num: Math.floor(i / 2) + 1,
      white: { label: props.moves[i], index: i + 1 },
      black: props.moves[i + 1] ? { label: props.moves[i + 1], index: i + 2 } : null
    })
  }
  return groups
})

// Auto-scroll to active move
watch(() => props.viewIndex, async () => {
  await nextTick()
  const active = scrollContainer.value?.querySelector('.move-btn.active')
  if (active) {
    active.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }
})
</script>

<template>
  <div class="move-history glass-panel">
    <div class="history-header">
      <span class="label">MOVE HISTORY</span>
    </div>
    <div class="move-list" ref="scrollContainer">
      <div v-if="moves.length === 0" class="empty-msg">No moves yet</div>
      <div v-for="group in groupedMoves" :key="group.num" class="move-row">
        <span class="move-num">{{ group.num }}.</span>
        <div class="move-pair">
          <button 
            :class="['move-btn', { active: viewIndex === group.white.index }]"
            @click="emit('jump', group.white.index)"
          >
            {{ group.white.label }}
          </button>
          <button 
            v-if="group.black"
            :class="['move-btn', { active: viewIndex === group.black.index }]"
            @click="emit('jump', group.black.index)"
          >
            {{ group.black.label }}
          </button>
        </div>
      </div>
    </div>

    <div class="navigation-controls">
      <button @click="emit('jump', 0)" class="nav-btn" title="First Position">⏮</button>
      <button @click="emit('command', 'back')" class="nav-btn" title="Previous Move">⏴</button>
      <button @click="emit('command', 'forward')" class="nav-btn" title="Next Move">⏵</button>
      <button @click="emit('command', 'last')" class="nav-btn" title="Last Position">⏭</button>
    </div>
  </div>
</template>

<style scoped>
.move-history {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 200px;
}

.history-header {
  padding: 1rem 1rem 0.5rem 1rem;
  border-bottom: 1px solid rgba(255,255,255,0.05);
}

.label {
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 0.15rem;
  color: #718096;
  text-transform: uppercase;
}

.move-list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.move-row {
  display: flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 4px;
}

.move-num {
  width: 30px;
  font-size: 0.75rem;
  color: #4a5568;
  font-weight: 600;
}

.move-pair {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
}

.move-btn {
  background: transparent;
  border: none;
  color: #cbd5e0;
  text-align: left;
  padding: 4px 8px;
  font-size: 0.85rem;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.2s;
}

.move-btn:hover {
  background: rgba(255,255,255,0.03);
  color: #fff;
}

.move-btn.active {
  background: rgba(78, 204, 163, 0.15) !important;
  color: #4ecca3 !important;
  font-weight: 700;
}

.empty-msg {
  padding: 2rem;
  text-align: center;
  color: #4a5568;
  font-size: 0.85rem;
  font-style: italic;
}

.navigation-controls {
  display: flex;
  justify-content: center;
  gap: 1.5rem;
  padding: 0.75rem;
  border-top: 1px solid rgba(255,255,255,0.05);
  background: rgba(0,0,0,0.1);
}

.nav-btn {
  background: transparent;
  border: none;
  color: #718096;
  font-size: 1.2rem;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nav-btn:hover {
  color: #4ecca3;
  transform: scale(1.1);
}

/* Custom Scrollbar */
.move-list::-webkit-scrollbar {
  width: 4px;
}
.move-list::-webkit-scrollbar-track {
  background: transparent;
}
.move-list::-webkit-scrollbar-thumb {
  background: rgba(255,255,255,0.05);
  border-radius: 2px;
}
.move-list::-webkit-scrollbar-thumb:hover {
  background: rgba(255,255,255,0.1);
}
</style>
