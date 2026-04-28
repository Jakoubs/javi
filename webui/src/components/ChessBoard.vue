<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps(['fen', 'flipped', 'highlights', 'selectedPos', 'hintHighlights'])
const emit = defineEmits(['square-click'])

const showPromotion = ref(false)
const promotionFrom = ref(null)
const promotionTo = ref(null)
const promotionColor = ref(null)

// The board layout logic remains robust
const board = computed(() => {
  const [position] = props.fen.split(' ')
  const rows = position.split('/')
  const result = []
  
  rows.forEach((row, rowIndex) => {
    let colIndex = 0
    for (const char of row) {
      if (isNaN(char)) {
        result.push({
          pos: String.fromCharCode(97 + colIndex) + (8 - rowIndex),
          piece: char,
          color: char === char.toUpperCase() ? 'white' : 'black',
          type: char.toLowerCase()
        })
        colIndex++
      } else {
        const count = parseInt(char)
        for (let i = 0; i < count; i++) {
          result.push({
            pos: String.fromCharCode(97 + colIndex) + (8 - rowIndex),
            piece: null
          })
          colIndex++
        }
      }
    }
  })
  return props.flipped ? result.reverse() : result
})

const handleSquareClick = (square) => {
  if (showPromotion.value) return 

  // Detect potential promotion BEFORE emitting (visual overlay logic is still local for UX)
  const fromSquare = board.value.find(s => s.pos === props.selectedPos)
  const isPawn = fromSquare?.type === 'p'
  const isPromotionTarget = props.highlights?.includes(square.pos) && isPawn && (
    (fromSquare.color === 'white' && square.pos[1] === '8') ||
    (fromSquare.color === 'black' && square.pos[1] === '1')
  )

  if (isPromotionTarget) {
    promotionFrom.value = props.selectedPos
    promotionTo.value = square.pos
    promotionColor.value = fromSquare.color
    showPromotion.value = true
  } else {
    // Standard click emission to backend
    emit('square-click', square.pos)
  }
}

const handlePromoSelection = (type) => {
  emit('square-click', `${promotionFrom.value}${promotionTo.value}${type}`)
  showPromotion.value = false
  promotionFrom.value = null
  promotionTo.value = null
  promotionColor.value = null
}

const cancelPromotion = () => {
  showPromotion.value = false
}

// SVG Piece mappings
const getPieceSvg = (type, color) => {
  const unicodeMap = {
    p: '♟', r: '♜', n: '♞', b: '♝', q: '♛', k: '♚'
  }
  return unicodeMap[type] || ''
}

const squareColor = (pos) => {
  const col = pos.charCodeAt(0) - 97
  const row = 8 - parseInt(pos[1])
  return (col + row) % 2 === 0 ? 'light' : 'dark'
}
</script>

<template>
  <div class="chessboard-container">
    <div class="chessboard shadow-lg">
      <div 
        v-for="square in board" 
        :key="square.pos" 
        class="square" 
        :class="[squareColor(square.pos), { 
          selected: props.selectedPos === square.pos,
          'is-highlight': props.highlights?.includes(square.pos),
          'is-hint': props.hintHighlights?.includes(square.pos)
        }]"
        @click="handleSquareClick(square)"
      >
        <div v-if="square.piece" class="piece" :class="square.color">
          {{ getPieceSvg(square.type, square.color) }}
        </div>
        
        <!-- Target indicator (dot or ring) from backend highlights -->
        <div v-if="props.highlights?.includes(square.pos)" class="move-indicator">
          <div :class="square.piece ? 'capture-ring' : 'move-dot'"></div>
        </div>

        <div class="coord">{{ square.pos }}</div>
      </div>

      <!-- Promotion Overlay -->
      <Transition name="fade">
        <div v-if="showPromotion" class="promotion-overlay glass-panel" @click.self="cancelPromotion">
          <div class="promotion-card">
            <h3>PROMOTE TO</h3>
            <div class="promotion-choices">
              <div 
                v-for="type in ['q', 'r', 'b', 'n']" 
                :key="type" 
                class="promo-choice" 
                :class="promotionColor"
                @click="handlePromoSelection(type)"
              >
                <span class="promo-piece">{{ getPieceSvg(type, promotionColor) }}</span>
                <span class="promo-label">{{ type === 'q' ? 'Queen' : type === 'r' ? 'Rook' : type === 'b' ? 'Bishop' : 'Knight' }}</span>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.chessboard-container {
  position: relative;
}

.chessboard {
  display: grid;
  --square-size: var(--board-square-size, min(80px, 10vmin));
  grid-template-columns: repeat(8, var(--square-size));
  grid-template-rows: repeat(8, var(--square-size));
  border: 4px solid #2d3748;
  border-radius: 12px;
  position: relative;
  overflow: hidden;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
  margin: auto;
  background-color: #2d3748;
}

.square {
  width: var(--square-size);
  height: var(--square-size);
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
  cursor: pointer;
  transition: all 0.2s;
}

.square.light {
  background-color: #ebecd0;
}

.square.dark {
  background-color: #779556;
}

.square.selected {
  background-color: #f6f669 !important;
}

.square.is-highlight:hover {
  filter: brightness(1.1);
}

.square.is-hint {
  background-color: rgba(78, 204, 163, 0.6) !important;
  box-shadow: inset 0 0 10px rgba(0,0,0,0.3);
}

.move-indicator {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 10;
  pointer-events: none;
}

.move-dot {
  width: 25%;
  height: 25%;
  background: rgba(0, 0, 0, 0.15);
  border-radius: 50%;
}

.capture-ring {
  width: 80%;
  height: 80%;
  border: 4px solid rgba(0, 0, 0, 0.1);
  border-radius: 50%;
}

.piece {
  font-size: calc(var(--square-size) * 0.85);
  user-select: none;
  transition: transform 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  filter: drop-shadow(0 4px 6px rgba(0,0,0,0.4));
  z-index: 20;
}

.piece:hover {
  transform: scale(1.15) translateY(-2px);
}

.piece.white {
  color: #fff;
  -webkit-text-stroke: 1.5px #000;
}

.piece.black {
  color: #000;
  -webkit-text-stroke: 1.5px #fff;
}

.coord {
  position: absolute;
  bottom: 2px;
  right: 2px;
  font-size: calc(var(--square-size) * 0.15);
  color: rgba(0, 0, 0, 0.3);
  font-weight: 800;
  pointer-events: none;
}

.square.dark .coord {
  color: rgba(255, 255, 255, 0.3);
}

.promotion-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(15, 23, 42, 0.6);
  backdrop-filter: blur(12px);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 100;
}

.promotion-card {
  background: rgba(30, 41, 59, 0.8);
  padding: 2rem;
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.7);
  text-align: center;
}

.promotion-card h3 {
  color: #f8fafc;
  margin-bottom: 1.5rem;
  font-size: 0.8rem;
  font-weight: 900;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  opacity: 0.8;
}

.promotion-choices {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 1.25rem;
}

.promo-choice {
  display: flex;
  flex-direction: column;
  align-items: center;
  background: rgba(255, 255, 255, 0.05);
  padding: 1.25rem;
  border-radius: 16px;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  border: 1px solid rgba(255, 255, 255, 0.05);
}

.promo-choice:hover {
  background: rgba(255, 255, 255, 0.1);
  transform: scale(1.05);
  border-color: rgba(255, 255, 255, 0.2);
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.3);
}

.promo-piece {
  font-size: 52px;
  line-height: 1;
  filter: drop-shadow(0 4px 6px rgba(0,0,0,0.4));
}

.promo-label {
  font-size: 11px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.5);
  margin-top: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.promo-choice.white .promo-piece {
  color: #fff;
  -webkit-text-stroke: 1.5px #000;
}

.promo-choice.black .promo-piece {
  color: #000;
  -webkit-text-stroke: 1.5px #fff;
}

.fade-enter-active,
.fade-leave-active {
  transition: all 0.4s cubic-bezier(0.4, 0, 0.2, 1);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(10px);
}
</style>
