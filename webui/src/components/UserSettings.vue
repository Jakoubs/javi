<script setup>
const props = defineProps({
  settings: {
    type: Object,
    required: true
  },
  serverInput: {
    type: String,
    default: ''
  },
  serverConnected: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:settings', 'update:serverInput', 'applyServer', 'resetServer', 'close'])

const toggle = (key) => {
  emit('update:settings', { ...props.settings, [key]: !props.settings[key] })
}
</script>

<template>
  <div class="settings-overlay" @click.self="emit('close')">
    <div class="settings-panel glass">
      <button class="close-x" @click="emit('close')">×</button>

      <div class="settings-header">
        <div class="settings-icon">⚙️</div>
        <h2>Einstellungen</h2>
        <p class="settings-subtitle">Passe dein Spielerlebnis an</p>
      </div>

      <div class="settings-body">

        <!-- Watch Mode Section -->
        <div class="settings-section">
          <div class="section-label">
            <span class="section-icon">👁</span>
            Watch-Modus
          </div>
          <div class="settings-item">
            <div class="item-info">
              <span class="item-title">Watch-Modus aktivieren</span>
              <span class="item-desc">Andere können deine laufenden Spiele als Zuschauer beobachten</span>
            </div>
            <button
              :class="['toggle-btn', { active: settings.watchModeEnabled }]"
              @click="toggle('watchModeEnabled')"
              :title="settings.watchModeEnabled ? 'Deaktivieren' : 'Aktivieren'"
            >
              <span class="toggle-knob"></span>
            </button>
          </div>
          <div class="settings-item">
            <div class="item-info">
              <span class="item-title">Zuschauerzahl anzeigen</span>
              <span class="item-desc">Badge mit Zuschauerzahl im Spiel einblenden</span>
            </div>
            <button
              :class="['toggle-btn', { active: settings.showSpectatorCount }]"
              @click="toggle('showSpectatorCount')"
            >
              <span class="toggle-knob"></span>
            </button>
          </div>
        </div>

        <div class="settings-divider"></div>

        <!-- Emotes Section -->
        <div class="settings-section">
          <div class="section-label">
            <span class="section-icon">🎉</span>
            Emotes
          </div>
          <div class="settings-item">
            <div class="item-info">
              <span class="item-title">Emotes anzeigen</span>
              <span class="item-desc">Emotes anderer Spieler & Zuschauer einblenden</span>
            </div>
            <button
              :class="['toggle-btn', { active: settings.showEmotes }]"
              @click="toggle('showEmotes')"
            >
              <span class="toggle-knob"></span>
            </button>
          </div>
          <div class="settings-item">
            <div class="item-info">
              <span class="item-title">Emotes senden</span>
              <span class="item-desc">Emote-Leiste unterm Brett anzeigen</span>
            </div>
            <button
              :class="['toggle-btn', { active: settings.canSendEmotes }]"
              @click="toggle('canSendEmotes')"
            >
              <span class="toggle-knob"></span>
            </button>
          </div>
        </div>

        <div class="settings-divider"></div>

        <!-- Server Section -->
        <div class="settings-section">
          <div class="section-label">
            <span class="section-icon">🌐</span>
            Server
          </div>
          <div class="settings-item server-item">
            <div class="item-info">
              <span class="item-title">Server URL</span>
              <span class="item-desc">Backend-Adresse (Standard: relativ / localhost:8080)</span>
            </div>
            <span
              class="server-dot-inline"
              :class="{ connected: serverConnected, disconnected: !serverConnected }"
              :title="serverConnected ? 'Verbunden' : 'Nicht verbunden'"
            ></span>
          </div>
          <div class="server-input-row">
            <input
              id="settings-server-url-input"
              :value="serverInput"
              @input="emit('update:serverInput', $event.target.value)"
              @keyup.enter="emit('applyServer')"
              @blur="emit('applyServer')"
              class="glass-input server-url-input"
              placeholder="Relativ (Standard) oder http://host:8080"
              spellcheck="false"
            />
            <button class="reset-server-btn" @click="emit('resetServer')" title="Zurücksetzen">↺</button>
          </div>
        </div>

      </div>

      <div class="settings-footer">
        <span class="footer-note">Einstellungen werden automatisch gespeichert</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.75);
  backdrop-filter: blur(10px);
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.settings-panel {
  width: 100%;
  max-width: 460px;
  border-radius: 24px;
  padding: 0;
  overflow: hidden;
  box-shadow: 0 30px 80px rgba(0, 0, 0, 0.5);
  animation: panel-in 0.35s cubic-bezier(0.175, 0.885, 0.32, 1.275);
  position: relative;
}

@keyframes panel-in {
  from { opacity: 0; transform: translateY(30px) scale(0.95); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}

.close-x {
  position: absolute;
  top: 1.2rem;
  right: 1.4rem;
  background: none;
  border: none;
  color: rgba(255, 255, 255, 0.4);
  font-size: 1.6rem;
  cursor: pointer;
  line-height: 1;
  transition: color 0.2s, transform 0.2s;
  z-index: 1;
}
.close-x:hover { color: white; transform: rotate(90deg); }

.settings-header {
  padding: 2rem 2rem 1.5rem;
  text-align: center;
  background: linear-gradient(135deg, rgba(78,204,163,0.1), rgba(240,165,0,0.05));
  border-bottom: 1px solid rgba(255,255,255,0.06);
}

.settings-icon {
  font-size: 2.5rem;
  margin-bottom: 0.5rem;
  animation: spin-slow 8s linear infinite;
  display: inline-block;
}

@keyframes spin-slow {
  from { transform: rotate(0deg); }
  to   { transform: rotate(360deg); }
}

.settings-header h2 {
  margin: 0 0 0.25rem;
  font-size: 1.5rem;
  font-weight: 800;
  color: #4ecca3;
  letter-spacing: 1px;
}

.settings-subtitle {
  margin: 0;
  font-size: 0.85rem;
  color: rgba(255,255,255,0.45);
}

.settings-body {
  padding: 1.5rem 2rem;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.settings-section {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.section-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.7rem;
  font-weight: 800;
  text-transform: uppercase;
  letter-spacing: 2px;
  color: rgba(255,255,255,0.35);
  margin-bottom: 0.5rem;
}
.section-icon { font-size: 1rem; }

.settings-divider {
  height: 1px;
  background: rgba(255,255,255,0.07);
  margin: 1.2rem 0;
}

.settings-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.85rem 1rem;
  border-radius: 12px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.05);
  transition: background 0.2s;
}
.settings-item:hover { background: rgba(255,255,255,0.06); }

.item-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
}
.item-title {
  font-size: 0.9rem;
  font-weight: 600;
  color: rgba(255,255,255,0.9);
}
.item-desc {
  font-size: 0.75rem;
  color: rgba(255,255,255,0.35);
  line-height: 1.4;
}

/* Toggle Button */
.toggle-btn {
  width: 50px;
  height: 26px;
  border-radius: 13px;
  border: 1px solid rgba(255,255,255,0.15);
  background: rgba(255,255,255,0.08);
  cursor: pointer;
  position: relative;
  flex-shrink: 0;
  transition: background 0.3s, border-color 0.3s;
}
.toggle-btn.active {
  background: rgba(78, 204, 163, 0.4);
  border-color: #4ecca3;
  box-shadow: 0 0 12px rgba(78,204,163,0.3);
}
.toggle-knob {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(255,255,255,0.5);
  transition: transform 0.3s cubic-bezier(0.68, -0.55, 0.265, 1.55), background 0.3s;
}
.toggle-btn.active .toggle-knob {
  transform: translateX(24px);
  background: #4ecca3;
}

.server-dot-inline {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  display: inline-block;
  transition: background 0.4s, box-shadow 0.4s;
}
.server-dot-inline.connected {
  background: #4ecca3;
  box-shadow: 0 0 6px rgba(78, 204, 163, 0.7);
}
.server-dot-inline.disconnected {
  background: #ff6b6b;
  box-shadow: 0 0 6px rgba(255, 107, 107, 0.5);
}

.server-input-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.35rem;
}

.server-url-input {
  flex: 1;
  font-size: 0.82rem;
  padding: 8px 12px;
  border-radius: 10px;
  min-width: 0;
}

.reset-server-btn {
  background: rgba(255,255,255,0.07);
  border: 1px solid rgba(255,255,255,0.12);
  color: rgba(255,255,255,0.6);
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 1rem;
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.2s, color 0.2s;
}
.reset-server-btn:hover {
  background: rgba(78,204,163,0.15);
  color: #4ecca3;
}

.settings-footer {
  padding: 1rem 2rem;
  text-align: center;
  border-top: 1px solid rgba(255,255,255,0.06);
}
.footer-note {
  font-size: 0.72rem;
  color: rgba(255,255,255,0.25);
}
</style>
