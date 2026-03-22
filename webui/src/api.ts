export type PieceType = 'king' | 'queen' | 'rook' | 'bishop' | 'knight' | 'pawn'
export type Color = 'white' | 'black'

export interface PieceDto {
  pos: string // e.g. "e4"
  color: Color
  pieceType: PieceType
}

export interface MoveDto {
  from: string
  to: string
  promotion?: PieceType
}

export interface StateDto {
  activeColor: Color
  status: string
  lastMove?: MoveDto
  drawOffer: boolean
  pieces: PieceDto[]
}

export interface CommandResponse {
  state: StateDto
  message?: string
}

export interface MovesResponse {
  from: string
  targets: string[]
}

export interface MakeMoveRequest {
  from: string
  to: string
  promotion?: string
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
    ...init,
  })

  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(`${res.status} ${res.statusText}${text ? `: ${text}` : ''}`)
  }

  return (await res.json()) as T
}

export const ChessApi = {
  state(): Promise<StateDto> {
    return api<StateDto>('/api/state')
  },
  moves(from: string): Promise<MovesResponse> {
    return api<MovesResponse>(`/api/moves/${encodeURIComponent(from)}`)
  },
  move(req: MakeMoveRequest): Promise<CommandResponse> {
    return api<CommandResponse>('/api/move', {
      method: 'POST',
      body: JSON.stringify(req),
    })
  },
  newGame(): Promise<CommandResponse> {
    return api<CommandResponse>('/api/new', { method: 'POST', body: '{}' })
  },
  undo(): Promise<CommandResponse> {
    return api<CommandResponse>('/api/undo', { method: 'POST', body: '{}' })
  },
  draw(): Promise<CommandResponse> {
    return api<CommandResponse>('/api/draw', { method: 'POST', body: '{}' })
  },
  resign(): Promise<CommandResponse> {
    return api<CommandResponse>('/api/resign', { method: 'POST', body: '{}' })
  },
}

