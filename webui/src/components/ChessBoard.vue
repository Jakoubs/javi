<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps(['fen'])
const emit = defineEmits(['move'])

const selectedSquare = ref(null)
const legalMoves = ref([])

// Clear selection if FEN changes (move made)
watch(() => props.fen, () => {
  selectedSquare.value = null
  legalMoves.value = []
})

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
  return result
})

const activeColorLetter = computed(() => props.fen.split(' ')[1])

const fetchLegalMoves = async (square) => {
  try {
    const response = await fetch(`http://localhost:8080/api/legal-moves?square=${square}`)
    if (response.ok) {
      legalMoves.value = await response.json()
    }
  } catch (error) {
    console.error('Failed to fetch legal moves:', error)
  }
}

const handleSquareClick = async (square) => {
  // If we already have a selection
  if (selectedSquare.value) {
    // Clicked same square: deselect
    if (selectedSquare.value === square.pos) {
      selectedSquare.value = null
      legalMoves.value = []
    } 
    // Clicked a legal target: move
    else if (legalMoves.value.includes(square.pos)) {
      emit('move', `${selectedSquare.value}${square.pos}`)
      selectedSquare.value = null
      legalMoves.value = []
    }
    // Clicked another friendly piece: change selection
    else if (square.piece && square.color.startsWith(activeColorLetter.value === 'w' ? 'white' : 'black')) {
      selectedSquare.value = square.pos
      await fetchLegalMoves(square.pos)
    }
    // Clicked elsewhere: deselect
    else {
      selectedSquare.value = null
      legalMoves.value = []
    }
  } 
  // No current selection: select piece if it is the active color
  else if (square.piece && square.color.startsWith(activeColorLetter.value === 'w' ? 'white' : 'black')) {
    selectedSquare.value = square.pos
    await fetchLegalMoves(square.pos)
  }
}

// SVG Piece mappings (Standard High Quality - Wikipedia Style)
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
  <div class="chessboard">
    <div 
      v-for="square in board" 
      :key="square.pos" 
      class="square" 
      :class="[squareColor(square.pos), { 
        selected: selectedSquare === square.pos,
        'has-legal-move': legalMoves.includes(square.pos)
      }]"
      @click="handleSquareClick(square)"
    >
      <div v-if="square.piece" class="piece" :class="square.color">
        {{ getPieceSvg(square.type, square.color) }}
      </div>
      
      <!-- Legal move indicator shadow/dot -->
      <div v-if="legalMoves.includes(square.pos)" class="move-indicator">
        <div :class="square.piece ? 'capture-ring' : 'move-dot'"></div>
      </div>

      <div class="coord">{{ square.pos }}</div>
    </div>
  </div>
</template>

<style scoped>
.chessboard {
  display: grid;
  /* Use vmin to ensure it fits both width and height-constrained screens */
  --square-size: min(60px, 10vmin);
  grid-template-columns: repeat(8, var(--square-size));
  grid-template-rows: repeat(8, var(--square-size));
  border: 4px solid #2d3748;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.3);
  margin: auto;
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

/* Subtle highlight for squares that are targets of legal moves */
.square.has-legal-move:hover {
  filter: brightness(1.1);
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
  z-index: 1;
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
  font-size: calc(var(--square-size) * 0.75);
  user-select: none;
  transition: transform 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  filter: drop-shadow(0 2px 4px rgba(0,0,0,0.3));
  z-index: 2;
}

.piece:hover {
  transform: scale(1.1);
}

.piece.white {
  color: #fff;
  -webkit-text-stroke: 1px #000;
}

.piece.black {
  color: #000;
  -webkit-text-stroke: 1px #fff;
}

.coord {
  position: absolute;
  bottom: 2px;
  right: 2px;
  font-size: calc(var(--square-size) * 0.15);
  color: rgba(0, 0, 0, 0.4);
  font-weight: bold;
}

.square.dark .coord {
  color: rgba(255, 255, 255, 0.4);
}

@media (max-width: 1000px) {
  .chessboard {
    --square-size: min(11vw, 8vh);
  }
}
</style>
