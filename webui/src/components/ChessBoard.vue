<script setup lang="ts">
import type { PieceDto } from '../api'

const props = defineProps<{
  pieces: PieceDto[]
  lastMove?: { from: string; to: string }
  selected?: { from?: string; to?: string }
  highlights?: string[]
}>()

const emit = defineEmits<{
  squareClick: [square: string]
}>()

const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'] as const

function pieceAt(square: string): PieceDto | undefined {
  return props.pieces.find((p) => p.pos === square)
}

function unicodePiece(p: PieceDto): string {
  const key = `${p.color}:${p.pieceType}`
  switch (key) {
    case 'white:king':
      return '♔'
    case 'white:queen':
      return '♕'
    case 'white:rook':
      return '♖'
    case 'white:bishop':
      return '♗'
    case 'white:knight':
      return '♘'
    case 'white:pawn':
      return '♙'
    case 'black:king':
      return '♚'
    case 'black:queen':
      return '♛'
    case 'black:rook':
      return '♜'
    case 'black:bishop':
      return '♝'
    case 'black:knight':
      return '♞'
    case 'black:pawn':
      return '♟'
    default:
      return '?'
  }
}

function isLightSquare(fileIdx: number, rank: number): boolean {
  // rank is 1..8
  return (fileIdx + rank) % 2 === 0
}

function isLastMoveSquare(square: string): boolean {
  const lm = props.lastMove
  if (!lm) return false
  return square === lm.from || square === lm.to
}

function isSelected(square: string): boolean {
  return square === props.selected?.from || square === props.selected?.to
}

function isHighlighted(square: string): boolean {
  return props.highlights?.includes(square) ?? false
}
</script>

<template>
  <div class="frame" aria-label="Chess board frame">
    <div class="corner" />
    <div class="fileLabels" aria-hidden="true">
      <div v-for="f in files" :key="f" class="fileLabel">{{ f }}</div>
    </div>
    <div class="corner" />

    <div class="rankLabels" aria-hidden="true">
      <div v-for="r in 8" :key="r" class="rankLabel">{{ 9 - r }}</div>
    </div>

    <div class="board" role="grid" aria-label="Chess board">
      <div
        v-for="rank in 8"
        :key="rank"
        class="rank"
        role="row"
        :aria-label="`Rank ${9 - rank}`"
      >
        <button
          v-for="(file, fileIdx) in files"
          :key="file"
          type="button"
          class="square"
          role="gridcell"
          :class="{
            light: isLightSquare(fileIdx, 9 - rank),
            dark: !isLightSquare(fileIdx, 9 - rank),
            lastMove: isLastMoveSquare(`${file}${9 - rank}`),
            selected: isSelected(`${file}${9 - rank}`),
            highlight: isHighlighted(`${file}${9 - rank}`),
          }"
          :aria-label="`${file}${9 - rank}`"
          @click="emit('squareClick', `${file}${9 - rank}`)"
        >
          <span class="piece">
            {{
              (() => {
                const sq = `${file}${9 - rank}`
                const p = pieceAt(sq)
                return p ? unicodePiece(p) : ''
              })()
            }}
          </span>
        </button>
      </div>
    </div>

    <div class="rankLabels" aria-hidden="true">
      <div v-for="r in 8" :key="r" class="rankLabel">{{ 9 - r }}</div>
    </div>

    <div class="corner" />
    <div class="fileLabels" aria-hidden="true">
      <div v-for="f in files" :key="f" class="fileLabel">{{ f }}</div>
    </div>
    <div class="corner" />
  </div>
</template>

<style scoped>
.frame {
  display: grid;
  grid-template-columns: 24px 1fr 24px;
  grid-template-rows: 24px 1fr 24px;
  width: min(92vw, 560px);
  aspect-ratio: 1 / 1;
  gap: 8px;
}

.corner {
  display: grid;
}

.fileLabels {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  align-items: center;
  justify-items: center;
  color: rgba(255, 255, 255, 0.72);
  font-size: 12px;
  letter-spacing: 0.3px;
  user-select: none;
}

.rankLabels {
  display: grid;
  grid-template-rows: repeat(8, 1fr);
  align-items: center;
  justify-items: center;
  color: rgba(255, 255, 255, 0.72);
  font-size: 12px;
  letter-spacing: 0.3px;
  user-select: none;
}

.board {
  display: grid;
  grid-template-rows: repeat(8, 1fr);
  border-radius: 14px;
  overflow: hidden;
  box-shadow:
    0 10px 30px rgba(0, 0, 0, 0.25),
    0 1px 0 rgba(255, 255, 255, 0.06) inset;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.rank {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
}

.square {
  display: grid;
  place-items: center;
  font-size: 34px;
  user-select: none;
  border: none;
  padding: 0;
  cursor: pointer;
  background: transparent;
}

.light {
  background: #f0d9b5;
}
.dark {
  background: #b58863;
}

.lastMove {
  outline: 3px solid rgba(97, 218, 251, 0.65);
  outline-offset: -3px;
}

.selected {
  outline: 3px solid rgba(167, 139, 250, 0.75);
  outline-offset: -3px;
}

.highlight {
  box-shadow: inset 0 0 0 4px rgba(52, 211, 153, 0.55);
}

.square:focus-visible {
  outline: 3px solid rgba(97, 218, 251, 0.85);
  outline-offset: -3px;
}

.piece {
  filter: drop-shadow(0 2px 2px rgba(0, 0, 0, 0.35));
}
</style>

