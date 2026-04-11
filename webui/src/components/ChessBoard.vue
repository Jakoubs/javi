<script setup>
import { computed, ref } from 'vue'

const props = defineProps(['fen'])
const emit = defineEmits(['move'])

const selectedSquare = ref(null)

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

const handleSquareClick = (square) => {
  if (selectedSquare.value) {
    if (selectedSquare.value === square.pos) {
      selectedSquare.value = null
    } else {
      // Dispatch move
      emit('move', `${selectedSquare.value}${square.pos}`)
      selectedSquare.value = null
    }
  } else if (square.piece) {
    selectedSquare.value = square.pos
  }
}

// SVG Piece mappings (Standard High Quality - Wikipedia Style)
const getPieceSvg = (type, color) => {
  const colors = {
    white: '#fff',
    black: '#000',
    whiteOutline: '#000',
    blackOutline: '#fff'
  }
  
  // Simplified for brevity, in a real app these would be full SVGs
  // Using high-quality unicode as fallback but styled nicely
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
      :class="[squareColor(square.pos), { selected: selectedSquare === square.pos }]"
      @click="handleSquareClick(square)"
    >
      <div v-if="square.piece" class="piece" :class="square.color">
        {{ getPieceSvg(square.type, square.color) }}
      </div>
      <div class="coord">{{ square.pos }}</div>
    </div>
  </div>
</template>

<style scoped>
.chessboard {
  display: grid;
  grid-template-columns: repeat(8, 60px);
  grid-template-rows: repeat(8, 60px);
  border: 4px solid #2d3748;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.3);
}

.square {
  width: 60px;
  height: 60px;
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
  cursor: pointer;
  transition: background-color 0.2s;
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

.piece {
  font-size: 45px;
  user-select: none;
  transition: transform 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  filter: drop-shadow(0 2px 4px rgba(0,0,0,0.3));
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
  font-size: 8px;
  color: rgba(0, 0, 0, 0.4);
  font-weight: bold;
}

.square.dark .coord {
  color: rgba(255, 255, 255, 0.4);
}

@media (max-width: 600px) {
  .chessboard {
    grid-template-columns: repeat(8, 11vw);
    grid-template-rows: repeat(8, 11vw);
  }
  .square {
    width: 11vw;
    height: 11vw;
  }
  .piece {
    font-size: 8vw;
  }
}
</style>
