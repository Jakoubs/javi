<script setup>
const emit = defineEmits(['startWithTime'])

const presets = [
  { name: '1|0 Bullet', time: 60000, inc: 0 },
  { name: '3|2 Blitz', time: 180000, inc: 2000 },
  { name: '10|0 Rapid', time: 600000, inc: 0 },
  { name: 'Unlimited', time: null, inc: 0 }
]

const handleSelect = (p) => {
  emit('startWithTime', p.time, p.inc)
}
</script>

<template>
  <div class="menu-card glass">
    <label>Time Control</label>
    <div class="preset-grid">
      <button 
        v-for="p in presets" 
        :key="p.name"
        @click="handleSelect(p)"
        class="preset-btn"
      >
        {{ p.name }}
      </button>
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
  gap: 0.75rem;
}

label {
  font-size: 0.8rem;
  color: #aaa;
  font-weight: 600;
  text-transform: uppercase;
}

.preset-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
}

.preset-btn {
  background: rgba(255,255,255,0.1);
  border: 1px solid rgba(255,255,255,0.05);
  color: white;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.85rem;
  transition: all 0.2s;
}

.preset-btn:hover {
  background: rgba(255,255,255,0.2);
  border-color: rgba(255,255,255,0.2);
}
</style>
