<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import ChessBoard from './components/ChessBoard.vue'
import GameControls from './components/GameControls.vue'
import GameStatus from './components/GameStatus.vue'
import ImportExport from './components/ImportExport.vue'
import TimeSettings from './components/TimeSettings.vue'
import MoveHistory from './components/MoveHistory.vue'
import PuzzleView from './components/PuzzleView.vue'
import HomeView from './components/HomeView.vue'
import AdminPanel from './components/AdminPanel.vue'
import UserSettings from './components/UserSettings.vue'

const activeView = ref('home') // 'home', 'game', or 'puzzles'
const isSpectatorForced = ref(false)
const showNewGameModal = ref(false)
const showAdminPanel = ref(false)
const showFriendsModal = ref(false)
const showSettingsModal = ref(false)
const showEmotePopover = ref(false)
const incomingChallenges = ref([])
const challengeFeedback = ref('')
const challengeFeedbackError = ref(false)
const processedChallenges = ref([])
const isQueueing = ref(false)
const showCustomInModal = ref(false)
const customMin = ref(0)
const customInc = ref(0)

// ── Session & Role State (declared early to avoid TDZ) ───────────────────
const generateId = () => Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15)
const sessionId = ref(localStorage.getItem('chessSessionId') || generateId())
if (!localStorage.getItem('chessSessionId')) {
  localStorage.setItem('chessSessionId', sessionId.value)
}
const clientRole = ref(localStorage.getItem('chessClientRole') || 'white')
const currentParty = ref(localStorage.getItem('chessPartyCode') || '')

// ── User Settings ────────────────────────────────────────────────────────
const DEFAULT_SETTINGS = { watchModeEnabled: true, showSpectatorCount: true, showEmotes: true, canSendEmotes: true }
const savedSettings = JSON.parse(localStorage.getItem('chessUserSettings') || 'null')
const userSettings = ref({ ...DEFAULT_SETTINGS, ...savedSettings })
watch(userSettings, (val) => localStorage.setItem('chessUserSettings', JSON.stringify(val)), { deep: true })

// ── Emotes ───────────────────────────────────────────────────────────────
const EMOTES = ['👏', '🔥', '😮', '😂', '💀', '🎉', '😤', '❤️']
const activeEmotes = ref([])  // [{ id, emoji, lane }]
let emoteIdCounter = 0
const lastSeenEmoteId = ref(0)
let isFirstFetch = true

const addEmoteDisplay = (emoji) => {
  if (!userSettings.value.showEmotes) return
  const id = ++emoteIdCounter
  const lane = Math.floor(Math.random() * 5) // 0-4 vertical lanes
  activeEmotes.value.push({ id, emoji, lane })
  setTimeout(() => {
    activeEmotes.value = activeEmotes.value.filter(e => e.id !== id)
  }, 3500)
}

const sendEmote = (emoji) => {
  if (!userSettings.value.canSendEmotes) return
  addEmoteDisplay(emoji)
  try {
    watchChannel.postMessage({ type: 'emote', emoji, party: currentParty.value })
  } catch (e) {}
  
  // Also send to server for global visibility
  if (currentParty.value) {
    sendCommand(`emote ${emoji}`)
  }
}

// ── Spectator Count via BroadcastChannel ─────────────────────────────────
const spectatorCount = ref(0)
let watchChannel = null

const initWatchChannel = () => {
  try {
    watchChannel = new BroadcastChannel('javi-chess-watch')
    watchChannel.onmessage = (e) => {
      const { type, party, emoji } = e.data || {}
      if (party && party !== currentParty.value) return
      if (type === 'spectator-join') spectatorCount.value++
      else if (type === 'spectator-leave') spectatorCount.value = Math.max(0, spectatorCount.value - 1)
      else if (type === 'emote') addEmoteDisplay(emoji)
    }
  } catch (e) { watchChannel = null }
}

watch(() => clientRole.value, (newRole, oldRole) => {
  if (!watchChannel || !currentParty.value) return
  if (newRole === 'spectator') {
    watchChannel.postMessage({ type: 'spectator-join', party: currentParty.value })
  }
  if (oldRole === 'spectator') {
    watchChannel.postMessage({ type: 'spectator-leave', party: currentParty.value })
    spectatorCount.value = Math.max(0, spectatorCount.value - 1)
  }
})

watch(() => currentParty.value, () => {
  spectatorCount.value = 0
  lastSeenEmoteId.value = 0
  isFirstFetch = true
})

const timePresets = [
  { name: '1|0 Bullet', time: 60000, inc: 0 },
  { name: '3|2 Blitz', time: 180000, inc: 2000 },
  { name: '10|0 Rapid', time: 600000, inc: 0 }
]

// ── Server URL (persisted in localStorage) ────────────────────────────────
const DEFAULT_SERVER = 'http://localhost:8080'
const savedServer = localStorage.getItem('chessServerUrl')
const serverUrl = ref(savedServer && (savedServer.includes(':8080') || savedServer.includes(':8081')) ? savedServer : DEFAULT_SERVER)
const serverInput = ref(serverUrl.value)
const serverConnected = ref(true)

// ── Player Role (White / Black / Spectator) ──────────────────────────────

const setRole = (role) => {
  if (currentParty.value) return // Block switching in online games
  clientRole.value = role
  localStorage.setItem('chessClientRole', role)
}

// ── Local Play Mode ────────────────────────────────────────────────────────
// null = online/party mode, '2player' = two people on one keyboard,
// 'ai-white' = AI plays White (human is Black), 'ai-black' = AI plays Black (human is White)
const localMode = ref(null)

// ── User / Social State ──────────────────────────────────────────────────
const currentUser = ref(JSON.parse(localStorage.getItem('chessUser')))
const friends = ref([])
const friendRequests = ref([])
const newFriendName = ref('')
const friendFeedback = ref('')
const friendFeedbackError = ref(false)


const authMode = ref('login') // 'login' or 'register'
const showAuthModal = ref(false)
const authForm = ref({ username: '', email: '', password: '' })
const authError = ref('')

const handleAuth = async () => {
  authError.value = ''
  const endpoint = authMode.value === 'login' ? 'login' : 'register'
  try {
    const response = await fetch(`${serverUrl.value}/api/auth/${endpoint}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(authForm.value)
    })
    
    if (response.ok) {
      const user = await response.json()
      
      if (authMode.value === 'register' && !user.isVerified) {
        authError.value = "Check your email for a verification link!"
        authForm.value.password = ''
        return
      }

      currentUser.value = user
      localStorage.setItem('chessUser', JSON.stringify(user))
      showAuthModal.value = false
      fetchFriends()
      fetchFriendRequests()
    } else {
      const errText = await response.text()
      try {
        const errJson = JSON.parse(errText)
        authError.value = errJson.errorMessage || errJson.error || errJson
      } catch (e) {
        authError.value = errText
      }
    }
  } catch (e) {
    authError.value = 'Server connection failed'
  }
}

const logout = () => {
  currentUser.value = null
  localStorage.removeItem('chessUser')
  friends.value = []
}

const fetchFriends = async () => {
  if (!currentUser.value) return
  try {
    const response = await fetch(`${serverUrl.value}/api/social/friends?userId=${currentUser.value.id}`)
    if (response.ok) {
      friends.value = await response.json()
    }
  } catch (e) {}
}

const fetchFriendRequests = async () => {
  if (!currentUser.value) return
  try {
    const response = await fetch(`${serverUrl.value}/api/social/friends/requests?userId=${currentUser.value.id}`)
    if (response.ok) {
      friendRequests.value = await response.json()
    }
  } catch (e) {}
}


const challengeFriend = async (friendId) => {
  if (!currentUser.value) return
  challengeFeedback.value = ''
  challengeFeedbackError.value = false
  try {
    const response = await fetch(`${serverUrl.value}/api/social/challenge?userId=${currentUser.value.id}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ friendId })
    })
    if (response.ok) {
      challengeFeedback.value = 'Challenge sent! Waiting for opponent...'
      challengeFeedbackError.value = false
    } else {
      challengeFeedback.value = 'Failed to send challenge.'
      challengeFeedbackError.value = true
    }
  } catch (e) {
    challengeFeedback.value = 'Network error sending challenge.'
    challengeFeedbackError.value = true
  }
}

const acceptChallenge = async (partyCode) => {
  if (!currentUser.value) return
  try {
    const response = await fetch(`${serverUrl.value}/api/social/challenge/accept?userId=${currentUser.value.id}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ partyCode })
    })
    if (response.ok) {
      if (!processedChallenges.value.includes(partyCode)) {
        processedChallenges.value.push(partyCode)
      }
      partyCodeInput.value = partyCode
      joinParty()
      activeView.value = 'game'
      showFriendsModal.value = false
    }
  } catch (e) {}
}

const declineChallenge = async (partyCode) => {
  if (!currentUser.value) return
  try {
    await fetch(`${serverUrl.value}/api/social/challenge/decline?userId=${currentUser.value.id}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ partyCode })
    })
    incomingChallenges.value = incomingChallenges.value.filter(c => c.partyCode !== partyCode)
  } catch (e) {}
}

const addFriend = async () => {
  if (!newFriendName.value.trim() || !currentUser.value) return
  friendFeedback.value = ''
  friendFeedbackError.value = false
  const targetName = newFriendName.value.trim()
  try {
    const response = await fetch(`${serverUrl.value}/api/social/friends/add?userId=${currentUser.value.id}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ friendName: targetName })
    })
    if (response.ok) {
      newFriendName.value = ''
      friendFeedback.value = `Anfrage an '${targetName}' verschickt!`
      friendFeedbackError.value = false
      fetchFriends()
    } else {
      const errText = await response.text()
      if (errText.toLowerCase().includes("not found") || response.status === 404) {
        friendFeedback.value = `User '${targetName}' existiert nicht!`
      } else {
        friendFeedback.value = errText || 'Fehler beim Hinzufügen'
      }
      friendFeedbackError.value = true
    }
  } catch (e) {
    friendFeedback.value = 'Server-Fehler'
    friendFeedbackError.value = true
  }
}

const acceptFriend = async (friendId) => {
  if (!currentUser.value) return
  try {
    const response = await fetch(`${serverUrl.value}/api/social/friends/accept?userId=${currentUser.value.id}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ friendId })
    })
    if (response.ok) {
      fetchFriendRequests()
      fetchFriends()
    }
  } catch (e) {
    state.value.message = 'Failed to accept friend'
  }
}



// ── Party Code Logic ─────────────────────────────────────────────────────
const partyCodeInput = ref('')

const effectiveSessionId = computed(() => currentParty.value || sessionId.value)

const joinParty = () => {
  if (partyCodeInput.value.trim()) {
    currentParty.value = partyCodeInput.value.trim().toUpperCase()
    localStorage.setItem('chessPartyCode', currentParty.value)
    fetchState()
    partyCodeInput.value = ''
  }
}

const leaveParty = async () => {
  if (currentParty.value && currentUser.value) {
    try {
      await fetch(`${serverUrl.value}/api/party/leave?userId=${currentUser.value.id}&partyCode=${currentParty.value}`, { method: 'POST' })
    } catch (e) {
      console.error('Failed to notify server about leaving party', e)
    }
  }
  currentParty.value = ''
  localStorage.removeItem('chessPartyCode')
  fetchState()
}

const goToHome = () => {
  if (currentParty.value) {
    leaveParty()
  }
  activeView.value = 'home'
  localMode.value = null
}


const applyServer = () => {
  const url = serverInput.value.trim().replace(/\/$/, '')
  serverUrl.value = url
  serverInput.value = url
  localStorage.setItem('chessServerUrl', url)
  fetchState()
}
const resetServer = () => {
  serverInput.value = DEFAULT_SERVER
  applyServer()
}

const state = ref({
  fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  pgn: '',
  status: 'Playing',
  activeColor: 'White',
  highlights: [],
  selectedPos: null,
  lastMove: null,
  aiWhite: false,
  aiBlack: false,
  flipped: false,
  viewIndex: 0,
  historyFen: [],
  historyMoves: [],
  displayFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  clock: null,
  capturedWhite: [],
  capturedBlack: [],
  message: '',
  training: false,
  trainingProgress: null,
  whiteLiveMillis: 0,
  blackLiveMillis: 0,
  activePgnParser: 'regex',
  opening: null
})

const showGameOver = ref(false)
const processedStatus = ref(null)

const isGameOver = computed(() => {
  const s = state.value.status
  return s !== 'Playing' && !s.startsWith('Check(') && s !== ''
})

const gameOverInfo = computed(() => {
  const s = state.value.status
  if (s.startsWith('Checkmate')) {
    const winner = s.includes('White') ? 'Black' : 'White'
    return { title: 'Checkmate!', result: `${winner} Wins`, reason: s }
  }
  if (s.startsWith('Resigned')) {
    const winner = s.includes('White') ? 'Black' : 'White'
    return { title: 'Resignation', result: `${winner} Wins`, reason: s }
  }
  if (s.startsWith('Timeout')) {
    const winner = s.includes('White') ? 'Black' : 'White'
    return { title: 'Time Out!', result: `${winner} Wins`, reason: s }
  }
  if (s === 'Stalemate') return { title: 'Stalemate', result: 'Draw', reason: 'No legal moves left' }
  if (s.startsWith('Draw')) {
    // Try to extract reason from Draw(reason)
    const reason = s.match(/Draw\((.*)\)/)?.[1] || s
    return { title: 'Draw', result: 'Game is Drawn', reason: reason }
  }
  return { title: 'Game Over', result: '', reason: s }
})



const handleNewGameModal = () => {
  sendCommand('new')
  showGameOver.value = false
}

const handleQuitModal = () => {
  sendCommand('quit')
  showGameOver.value = false
}

const fetchState = async () => {
  try {
    const userQuery = currentUser.value ? `&username=${encodeURIComponent(currentUser.value.username)}` : ''
    const response = await fetch(`${serverUrl.value}/api/state?sessionId=${effectiveSessionId.value}&t=${Date.now()}${userQuery}`)
    if (response.ok) {
      serverConnected.value = true
      const data = await response.json()
      state.value = data
      
      // Proactive game-over check
      if (isGameOver.value && processedStatus.value !== data.status) {
        showGameOver.value = true
        processedStatus.value = data.status
      } else if (!isGameOver.value) {
        processedStatus.value = null
      }
      // Handle server-side emotes with deduplication
      if (data.recentEmotes && data.recentEmotes.length > 0) {
        if (isFirstFetch) {
          lastSeenEmoteId.value = Math.max(...data.recentEmotes.map(e => e.id))
        } else {
          data.recentEmotes.forEach(emote => {
            if (emote.id > lastSeenEmoteId.value) {
              addEmoteDisplay(emote.emoji)
              if (emote.id > lastSeenEmoteId.value) lastSeenEmoteId.value = emote.id
            }
          })
        }
      }
      isFirstFetch = false
    }
    
    if (currentUser.value) {
      if (isQueueing.value) {
        try {
          const qResp = await fetch(`${serverUrl.value}/api/queue/status?userId=${currentUser.value.id}`)
          if (qResp.ok) {
            const qData = await qResp.json()
            if (qData && qData.partyCode) {
              isQueueing.value = false
              partyCodeInput.value = qData.partyCode
              joinParty()
              activeView.value = 'game'
            }
          }
        } catch(e) {}
      }

      if (currentParty.value && !isSpectatorForced.value) {
        try {
          const roleResp = await fetch(`${serverUrl.value}/api/party/role?userId=${currentUser.value.id}&partyCode=${currentParty.value}`)
          if (roleResp.ok) {
            const roleData = await roleResp.json()
            if (roleData.role && roleData.role !== 'spectator') {
              clientRole.value = roleData.role
            }
          }
        } catch(e) {}
      }

      try {
        const respChall = await fetch(`${serverUrl.value}/api/social/challenges?userId=${currentUser.value.id}`)
        if (respChall.ok) {
          incomingChallenges.value = await respChall.json()
        }
        
        const respOut = await fetch(`${serverUrl.value}/api/social/challenges/outgoing?userId=${currentUser.value.id}`)
        if (respOut.ok) {
          const outgoing = await respOut.json()
          const acceptedMatch = outgoing.find(c => c.accepted)
          if (acceptedMatch && activeView.value === 'home' && !processedChallenges.value.includes(acceptedMatch.partyCode)) {
            processedChallenges.value.push(acceptedMatch.partyCode)
            partyCodeInput.value = acceptedMatch.partyCode
            joinParty()
            activeView.value = 'game'
            showFriendsModal.value = false
            
            await fetch(`${serverUrl.value}/api/social/challenge/decline?userId=${currentUser.value.id}`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ partyCode: acceptedMatch.partyCode })
            })
          }
        }
        
        const respFriends = await fetch(`${serverUrl.value}/api/social/friends?userId=${currentUser.value.id}`)
        if (respFriends.ok) {
          friends.value = await respFriends.json()
        }
        
        const respReqs = await fetch(`${serverUrl.value}/api/social/friends/requests?userId=${currentUser.value.id}`)
        if (respReqs.ok) {
          friendRequests.value = await respReqs.json()
        }
      } catch (e) {
        console.error('Failed to load friends/challenges state', e)
      }
    }
  } catch (e) {
    serverConnected.value = false
    console.error('Failed to fetch state', e)
  }
}

const sendCommand = async (cmd) => {
  if (cmd === 'new') {
    showNewGameModal.value = true
    return
  }
  
  const isBoardInteraction = /^[a-h][1-8]$/.test(cmd) || /^[a-h][1-8][a-h][1-8]/.test(cmd)
  const isGameAction = ['undo', 'resign'].includes(cmd)
  
  if (isBoardInteraction || isGameAction) {
    if (isGameOver.value && cmd !== 'undo') {
      return
    }

    if (localMode.value === '2player') {
      // Both sides can always interact – no restriction needed
    } else if (localMode.value === 'ai-white' || localMode.value === 'ai-black') {
      // Determine which color the human controls
      const humanColor = localMode.value === 'ai-white' ? 'Black' : 'White'
      // Spectator-style block during AI turn
      if (state.value.activeColor !== humanColor) {
        return
      }
    } else {
      // Online / party mode: original role-based gating
      if (clientRole.value === 'spectator') {
        return
      }
      const myTurn = (clientRole.value === 'white' && state.value.activeColor === 'White') ||
                     (clientRole.value === 'black' && state.value.activeColor === 'Black')
      if (!myTurn) {
        return
      }
    }
  }

  try {
    await fetch(`${serverUrl.value}/api/command?sessionId=${effectiveSessionId.value}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ command: cmd })
    })
    await fetchState()

    // In AI mode: after a human board interaction, let the AI respond automatically
    if ((localMode.value === 'ai-white' || localMode.value === 'ai-black') && isBoardInteraction && !isGameOver.value) {
      const humanColor = localMode.value === 'ai-white' ? 'Black' : 'White'
      // If after our move it's now the AI's turn, trigger ai move
      if (state.value.activeColor !== humanColor && !isGameOver.value) {
        setTimeout(async () => {
          await fetch(`${serverUrl.value}/api/command?sessionId=${effectiveSessionId.value}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ command: 'ai' })
          })
          fetchState()
        }, 150)
      }
    }
  } catch (e) {
    console.error('Failed to send command', e)
  }
}

const handleLoadFen = (fen) => sendCommand(`load fen ${fen}`)
const handleLoadPgn = (pgn) => sendCommand(`load pgn ${pgn}`)
const handleStartWithTime = (time, inc) => {
  if (time === null) sendCommand('start none')
  else sendCommand(`start ${time} ${inc}`)
}

const handleJoinQueue = async (timeMs, incMs) => {
  if (!currentUser.value) {
    showAuthModal.value = true
    return
  }
  isSpectatorForced.value = false
  isQueueing.value = true
  try {
    const response = await fetch(`${serverUrl.value}/api/queue/join?userId=${currentUser.value.id}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ timeMs, incMs })
    })
    if (response.ok) {
      const data = await response.json()
      if (data.matched && data.partyCode) {
        isQueueing.value = false
        partyCodeInput.value = data.partyCode
        joinParty()
        activeView.value = 'game'
      }
      // else: in queue, polling via fetchState will pick up the match
    } else {
      isQueueing.value = false
    }
  } catch (e) {
    console.error('Queue join failed', e)
    isQueueing.value = false
  }
}

const handleCancelQueue = async () => {
  isQueueing.value = false
  if (!currentUser.value) return
  try {
    await fetch(`${serverUrl.value}/api/queue/leave?userId=${currentUser.value.id}`, {
      method: 'DELETE'
    })
  } catch (e) {
    console.error('Queue leave failed', e)
  }
}

const handleStartGameFromHome = (timeMs, incMs, mode = null) => {
  isSpectatorForced.value = false
  localMode.value = mode
  // For AI modes set the role so the board flips correctly
  if (mode === 'ai-black') {
    clientRole.value = 'white'
  } else if (mode === 'ai-white') {
    clientRole.value = 'black'
  } else if (mode === '2player') {
    clientRole.value = 'white' // just use white perspective, flip is server-driven
  }
  handleStartWithTime(timeMs, incMs)
  activeView.value = 'game'
  showNewGameModal.value = false
}

const handleSwitchParser = (event) => {
  const variant = event.target.value
  sendCommand(`parser pgn ${variant}`)
}

const handleSpectateParty = (code) => {
  if (code && code.trim()) {
    currentParty.value = code.trim().toUpperCase()
    localStorage.setItem('chessPartyCode', currentParty.value)
    isSpectatorForced.value = true
    clientRole.value = 'spectator'
    localStorage.setItem('chessClientRole', 'spectator')
    activeView.value = 'game'
    fetchState()
  }
}

let pollInterval
let tickerInterval

onMounted(() => {
  fetchState()
  fetchFriends()
  fetchFriendRequests()
  initWatchChannel()

  // Announce spectator join if already in spectator role
  if (clientRole.value === 'spectator' && currentParty.value) {
    try { watchChannel?.postMessage({ type: 'spectator-join', party: currentParty.value }) } catch(e) {}
  }

  pollInterval = setInterval(fetchState, 500)
  
  tickerInterval = setInterval(() => {
    if (state.value.status === 'Playing' && state.value.clock && state.value.clock.isActive && state.value.clock.lastTickSysTime) {
      if (state.value.activeColor === 'White') {
        state.value.whiteLiveMillis = Math.max(0, state.value.whiteLiveMillis - 100)
      } else {
        state.value.blackLiveMillis = Math.max(0, state.value.blackLiveMillis - 100)
      }
    }
  }, 100)
})

onUnmounted(() => {
  clearInterval(pollInterval)
  clearInterval(tickerInterval)
  if (clientRole.value === 'spectator' && currentParty.value) {
    try { watchChannel?.postMessage({ type: 'spectator-leave', party: currentParty.value }) } catch(e) {}
  }
  try { watchChannel?.close() } catch(e) {}
})


const topPlayer = computed(() => {
  const isFlipped = state.value.flipped
  const mat = state.value.materialInfo
  return {
    name: isFlipped ? 'White Player' : 'Black Player',
    color: isFlipped ? 'White' : 'Black',
    captured: mat ? (isFlipped ? mat.blackCapturedSymbols : mat.whiteCapturedSymbols) : [],
    advantage: mat ? (isFlipped ? mat.whiteAdvantage : mat.blackAdvantage) : 0,
    clock: state.value.clock ? (isFlipped ? state.value.whiteLiveMillis : state.value.blackLiveMillis) : null
  }
})

const bottomPlayer = computed(() => {
  const isFlipped = state.value.flipped
  const mat = state.value.materialInfo
  return {
    name: isFlipped ? 'Black Player' : 'White Player',
    color: isFlipped ? 'Black' : 'White',
    captured: mat ? (isFlipped ? mat.whiteCapturedSymbols : mat.blackCapturedSymbols) : [],
    advantage: mat ? (isFlipped ? mat.blackAdvantage : mat.whiteAdvantage) : 0,
    clock: state.value.clock ? (isFlipped ? state.value.blackLiveMillis : state.value.whiteLiveMillis) : null
  }
})

const formatTime = (ms) => {
  if (ms === null || ms === undefined) return ''
  const totalSec = Math.max(0, Math.floor(ms / 1000))
  const m = Math.floor(totalSec / 60)
  const s = totalSec % 60
  return `${m}:${s.toString().padStart(2, '0')}`
}

const isViewingHistory = computed(() => {
  const h = state.value.historyFen
  const i = state.value.viewIndex
  return h && h.length > 0 && i < h.length - 1
})

const effectiveFlipped = computed(() => {
  // In local AI mode: orient board from the human player's perspective
  if (localMode.value === 'ai-white') return true   // human is Black → flip
  if (localMode.value === 'ai-black') return false  // human is White → normal
  if (localMode.value === '2player') return state.value.flipped // server-driven flip
  // Online / party mode
  if (clientRole.value === 'white') return false
  if (clientRole.value === 'black') return true
  return state.value.flipped
})
</script>

<template>
  <div class="app-container">
    <header class="glass">
      <div class="header-left">
        <div class="brand-group clickable-brand" @click="goToHome">
          <h1>JAVI CHESS</h1>
          <div class="premium-tag">Premium Chess Experience</div>
        </div>

        <div class="parser-switcher" v-show="activeView === 'game'">
          <label for="parser-select">PGN Parser:</label>
          <select id="parser-select" :value="state.activePgnParser" @change="handleSwitchParser" class="glass-select">
            <option value="regex">Regex</option>
            <option value="fast">Fastparse</option>
            <option value="combinator">Combinator</option>
          </select>
        </div>
        <div v-if="state.opening && activeView === 'game'" class="opening-badge shadow-sm">
          <span class="label">OPENING:</span>
          <span class="name">{{ state.opening }}</span>
        </div>
      </div>

      <!-- Party Mode UI -->
      <div class="server-switcher party-mode" v-show="activeView === 'game'">
        <div v-if="!currentParty" class="party-input-group">
          <input 
            v-model="partyCodeInput" 
            placeholder="Party Code (e.g. ABCD)" 
            class="config-input party-input glass-input"
            @keyup.enter="joinParty"
            spellcheck="false"
          >
          <button @click="joinParty" class="server-reset-btn" title="Join Party">Join</button>
        </div>
        <div v-else class="party-active glass-pill">
          <span class="active-label">Party:</span>
          <span class="active-code">{{ currentParty }}</span>
          <!-- Spectator Count Badge -->
          <div class="spectator-group" v-if="userSettings.watchModeEnabled">
            <span
              v-if="userSettings.showSpectatorCount && (spectatorCount > 0 || (state.activeUsers && state.activeUsers.length > 0))"
              class="spectator-badge"
              :title="state.activeUsers ? 'Zuschauer: ' + state.activeUsers.join(', ') : 'Zuschauer'"
            >
              👁 {{ Math.max(spectatorCount, state.activeUsers ? state.activeUsers.length : 0) }}
            </span>
          </div>
          <button @click="leaveParty" class="server-reset-btn leave-btn" title="Leave Party">✕</button>
        </div>
      </div>

      <!-- Server URL switcher -->
      <div class="server-switcher" v-show="activeView === 'game'">
        <span class="server-dot" :class="{ connected: serverConnected, disconnected: !serverConnected }" :title="serverConnected ? 'Connected' : 'Disconnected'"></span>
        <input
          id="server-url-input"
          v-model="serverInput"
          class="server-input glass-input"
          placeholder="Relative (default) or http://host:8080"
          @keyup.enter="applyServer"
          @blur="applyServer"
          spellcheck="false"
        />
        <button class="server-reset-btn" @click="resetServer" title="Reset to localhost">↺</button>
      </div>

      <!-- Player Role Selector (only for online/party play) -->
      <div class="role-selector glass-pill" v-show="activeView === 'game' && !localMode" :style="{ opacity: currentParty ? 0.6 : 1, pointerEvents: currentParty ? 'none' : 'auto' }">
        <button 
          v-for="role in ['white', 'black', 'spectator']" 
          :key="role"
          :class="['role-btn', role, { active: clientRole === role }]"
          @click="setRole(role)"
        >
          {{ role === 'spectator' ? '👁' : (role === 'white' ? '♔' : '♚') }}
          <span class="role-label">{{ role.charAt(0).toUpperCase() + role.slice(1) }}</span>
        </button>
      </div>

      <!-- Local Mode Indicator (shown when playing locally) -->
      <div v-if="activeView === 'game' && localMode" class="local-mode-badge glass-pill">
        <span v-if="localMode === '2player'">👥 2 Spieler Lokal</span>
        <span v-else-if="localMode === 'ai-black'">🤖 KI spielt Schwarz</span>
        <span v-else-if="localMode === 'ai-white'">🤖 KI spielt Weiß</span>
      </div>

      <!-- User Account -->
      <div class="user-account">
        <div v-if="!currentUser" class="auth-trigger" @click="showAuthModal = true">
          <span class="user-icon">👤</span>
          <span class="auth-label">Login / Register</span>
        </div>
        <div v-else class="user-profile glass-pill">
          <span class="username">{{ currentUser.username }}</span>
          <button v-if="currentUser.username === 'admin'" @click="showAdminPanel = true" class="admin-nav-btn" style="background: rgba(255,255,255,0.1); border: none; color: #4ecca3; margin-right: 10px; cursor: pointer; font-size: 0.9rem;">⚙️ Admin</button>
          <button @click="showSettingsModal = true" class="settings-trigger-btn" title="Einstellungen">⚙</button>
          <button @click="logout" class="logout-btn">Logout</button>
        </div>
        <!-- Settings trigger when not logged in -->
        <button v-if="!currentUser" @click="showSettingsModal = true" class="settings-trigger-btn standalone" title="Einstellungen">⚙</button>
      </div>
    </header>

    <!-- Home View -->
    <main v-if="activeView === 'home'" class="home-main-container">
      <!-- Friends Icon Trigger -->
      <button v-if="currentUser" @click="showFriendsModal = true" class="friends-trigger-btn" style="position: fixed; left: 2rem; top: 120px; background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.1); border-radius: 50%; width: 45px; height: 45px; display: flex; align-items: center; justify-content: center; color: white; cursor: pointer; font-size: 1.25rem; transition: background 0.2s, transform 0.2s; z-index: 10;" title="Friends">
        👥
        <span v-if="incomingChallenges.length > 0" style="position: absolute; top: -2px; right: -2px; width: 12px; height: 12px; background: #ff6b6b; border-radius: 50%; border: 2px solid #1a1a2e; animation: pulse 2s infinite;"></span>
      </button>

      <!-- Friends Modal Overlay -->
      <div v-if="showFriendsModal && currentUser" class="modal-overlay" @click.self="showFriendsModal = false" style="z-index: 100;">
        <div class="modal-content glass friends-modal" style="max-width: 400px; padding: 2rem; position: relative; border-radius: 16px;">
          <button class="close-x" @click="showFriendsModal = false" style="position: absolute; right: 1.25rem; top: 1.25rem; background: none; border: none; color: rgba(255,255,255,0.5); font-size: 1.5rem; cursor: pointer; transition: color 0.2s;">&times;</button>
          
          <div class="sidebar-header" style="margin-bottom: 1.5rem;">
            <h3 style="margin: 0 0 1rem 0; font-size: 1.5rem; color: #4ecca3;">Friends</h3>
            <div class="add-friend-form" style="display: flex; gap: 0.5rem; margin-bottom: 0.5rem;">
              <input v-model="newFriendName" placeholder="Username..." @keyup.enter="addFriend" class="glass-input" style="padding: 10px; border-radius: 8px; font-size: 0.9rem;">
              <button @click="addFriend" class="add-btn" style="padding: 0 25px; border-radius: 8px; background: #4ecca3; color: #1a1a2e; border: none; font-weight: bold; cursor: pointer; display: flex; align-items: center; justify-content: center;">Add</button>
            </div>
            <p v-if="friendFeedback" :style="{ color: friendFeedbackError ? '#ff6b6b' : '#4ecca3', fontSize: '0.85rem', marginTop: '6px', marginBottom: '0', textAlign: 'left', opacity: 0.9 }">
              {{ friendFeedback }}
            </p>
          </div>

          <!-- Friend Requests -->
          <div class="friend-requests" v-if="friendRequests.length > 0" style="margin-bottom: 1.5rem;">
            <h4 style="margin: 0 0 0.75rem 0; font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; color: rgba(255,255,255,0.5);">Requests</h4>
            <div v-for="req in friendRequests" :key="req.id" class="friend-item glass-pill incoming-req" style="display: flex; justify-content: space-between; align-items: center; padding: 10px 15px; border-radius: 12px; background: rgba(255,255,255,0.05); margin-bottom: 0.5rem;">
              <span class="friend-name" style="font-weight: 600;">{{ req.username }}</span>
              <button @click="acceptFriend(req.id)" class="accept-btn" style="background: rgba(78,204,163,0.2); color: #4ecca3; border: 1px solid rgba(78,204,163,0.3); padding: 4px 10px; border-radius: 6px; font-size: 0.8rem; cursor: pointer; font-weight: 600;">Accept</button>
            </div>
          </div>

          <!-- Incoming Challenges -->
          <div class="incoming-challenges" v-if="incomingChallenges.length > 0" style="margin-bottom: 1.5rem;">
            <h4 style="margin: 0 0 0.75rem 0; font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; color: #f0a500;">Game Challenges</h4>
            <div v-for="chal in incomingChallenges" :key="chal.partyCode" class="friend-item glass-pill incoming-challenge" style="display: flex; justify-content: space-between; align-items: center; padding: 10px 15px; border-radius: 12px; background: rgba(240,165,0,0.1); border: 1px solid rgba(240,165,0,0.2); margin-bottom: 0.5rem;">
              <span class="friend-name" style="font-weight: 600;">{{ chal.challengerName }}</span>
              <div style="display: flex; gap: 0.5rem;">
                <button @click="acceptChallenge(chal.partyCode)" class="accept-btn" style="background: rgba(78,204,163,0.2); color: #4ecca3; border: 1px solid rgba(78,204,163,0.3); padding: 4px 10px; border-radius: 6px; font-size: 0.8rem; cursor: pointer; font-weight: 600;">Accept</button>
                <button @click="declineChallenge(chal.partyCode)" class="decline-btn" style="background: rgba(255,107,107,0.2); color: #ff6b6b; border: 1px solid rgba(255,107,107,0.3); padding: 4px 10px; border-radius: 6px; font-size: 0.8rem; cursor: pointer; font-weight: 600;">Decline</button>
              </div>
            </div>
          </div>

          <!-- Friends List -->
          <h4 style="margin: 0 0 0.75rem 0; font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; color: rgba(255,255,255,0.5);">Your Friends</h4>
          <p v-if="challengeFeedback" :style="{ color: challengeFeedbackError ? '#ff6b6b' : '#4ecca3', fontSize: '0.85rem', marginBottom: '10px', marginTop: '-5px', textAlign: 'left', opacity: 0.9 }">
            {{ challengeFeedback }}
          </p>
          <div class="friends-list" style="max-height: 250px; overflow-y: auto;">
            <div v-for="friend in friends" :key="friend.id" class="friend-item glass-pill" style="display: flex; justify-content: space-between; align-items: center; padding: 10px 15px; border-radius: 12px; background: rgba(255,255,255,0.03); margin-bottom: 0.5rem;">
              <span class="friend-name" style="font-weight: 600;">{{ friend.username }}</span>
              <button @click="challengeFriend(friend.id)" class="challenge-btn" style="background: rgba(240,165,0,0.2); color: #f0a500; border: 1px solid rgba(240,165,0,0.3); padding: 4px 10px; border-radius: 6px; font-size: 0.8rem; cursor: pointer; font-weight: 600;">Challenge</button>
            </div>
            <div v-if="friends.length === 0" class="no-friends" style="text-align: center; color: rgba(255,255,255,0.3); padding: 1.5rem 0;">
              <p style="margin: 0;">No friends yet.</p>
            </div>
          </div>
        </div>
      </div>

      <HomeView 
        :isQueueing="isQueueing"
        :serverUrl="serverUrl"
        @start-game="handleStartGameFromHome" 
        @join-queue="handleJoinQueue"
        @cancel-queue="handleCancelQueue"
        @goto-puzzles="activeView = 'puzzles'" 
        @spectate-party="handleSpectateParty"
      />
    </main>

    <!-- Puzzle View -->
    <main v-else-if="activeView === 'puzzles'">
      <PuzzleView :serverUrl="serverUrl" />
    </main>

    <!-- Game View -->
    <main v-else>
      <div class="left-panel">
        <GameStatus :state="state" />
        <MoveHistory 
          :moves="state.historyMoves || []" 
          :viewIndex="state.viewIndex" 
          @jump="(i) => sendCommand(`jump ${i}`)" 
          @command="sendCommand"
        />
      </div>

      <div class="board-container">
        <!-- Top Player Info -->
        <div class="player-info top">
          <div class="player-meta">
            <span class="p-name">{{ topPlayer.name }}</span>
            <div class="captured-list">
              <span v-for="(s, i) in topPlayer.captured" :key="i" class="piece-icon">{{ s }}</span>
              <span v-if="topPlayer.advantage > 0" class="advantage">+{{ topPlayer.advantage }}</span>
            </div>
          </div>
          <div :class="['clock glass', { active: state.activeColor === topPlayer.color }]">
            {{ formatTime(topPlayer.clock) }}
          </div>
        </div>

        <ChessBoard 
          :fen="state.displayFen" 
          :flipped="effectiveFlipped"
          :highlights="isViewingHistory ? [] : state.highlights"
          :selectedPos="isViewingHistory ? null : state.selectedPos"
          @square-click="(pos) => sendCommand(pos)"
        />

        <!-- Bottom Player Info -->
        <div class="player-info bot">
          <div class="player-meta">
            <span class="p-name">{{ bottomPlayer.name }}</span>
            <div class="captured-list">
              <span v-for="(s, i) in bottomPlayer.captured" :key="i" class="piece-icon">{{ s }}</span>
              <span v-if="bottomPlayer.advantage > 0" class="advantage">+{{ bottomPlayer.advantage }}</span>
            </div>
          </div>
          <div :class="['clock glass', { active: state.activeColor === bottomPlayer.color }]">
            {{ formatTime(bottomPlayer.clock) }}
          </div>
        </div>
      </div>

      <!-- Emote Bar (Compact Popover Version) -->
      <div v-if="activeView === 'game' && userSettings.canSendEmotes" class="emote-popover-wrapper">
        <button 
          class="emote-trigger-btn glass" 
          @click="showEmotePopover = !showEmotePopover"
          title="Send Emote"
        >
          <span>😊</span>
        </button>

        <transition name="popover-fade">
          <div v-if="showEmotePopover" class="emote-popover glass">
            <div class="emote-popover-grid">
              <button
                v-for="emoji in EMOTES"
                :key="emoji"
                class="emote-pop-btn"
                @click="sendEmote(emoji)"
                :title="emoji"
              >{{ emoji }}</button>
            </div>
          </div>
        </transition>
      </div>

      <div class="right-panel" v-if="clientRole !== 'spectator'">

        <ImportExport 
          :fen="state.fen" 
          :pgn="state.pgn" 
          @loadFen="handleLoadFen" 
          @loadPgn="handleLoadPgn"
        />
        <GameControls :state="state" @command="sendCommand" />
      </div>
    </main>

    <!-- Emote Overlay (right edge) -->
    <teleport to="body">
      <div class="emote-overlay" v-if="activeView === 'game'">
        <transition-group name="emote-pop" tag="div" class="emote-stream">
          <div
            v-for="e in activeEmotes"
            :key="e.id"
            class="emote-float"
            :style="{ '--lane': e.lane }"
          >{{ e.emoji }}</div>
        </transition-group>
      </div>
    </teleport>

    <!-- Game Over Modal -->
    <div v-if="showGameOver" class="modal-overlay">
      <div class="modal-content glass">
        <button class="close-x" @click="showGameOver = false">&times;</button>
        <div class="modal-header">
          <h2>{{ gameOverInfo.title }}</h2>
        </div>
        <div class="modal-body">
          <div class="result-text">{{ gameOverInfo.result }}</div>
          <div class="reason-text">{{ gameOverInfo.reason }}</div>
        </div>
        <div class="modal-footer">
          <button class="btn primary" @click="handleNewGameModal">New Game</button>
          <button class="btn" @click="handleQuitModal">Quit</button>
        </div>
      </div>
    </div>
  </div>

  <!-- Auth Modal -->
  <div v-if="showAuthModal" class="modal-overlay" @click.self="showAuthModal = false">
    <div class="modal-content glass">
      <h2>{{ authMode === 'login' ? 'Welcome Back' : 'Join Javi Chess' }}</h2>
      <div class="auth-tabs">
        <button :class="{ active: authMode === 'login' }" @click="authMode = 'login'">Login</button>
        <button :class="{ active: authMode === 'register' }" @click="authMode = 'register'">Register</button>
      </div>
      
      <form @submit.prevent="handleAuth" class="auth-form">
        <div class="form-group">
          <input v-model="authForm.username" placeholder="Username" required class="glass-input">
        </div>
        <div v-if="authMode === 'register'" class="form-group">
          <input v-model="authForm.email" type="email" placeholder="Email Address" required class="glass-input">
        </div>
        <div class="form-group">
          <input v-model="authForm.password" type="password" placeholder="Password" required class="glass-input">
        </div>
        <p v-if="authError" class="auth-error">{{ authError }}</p>
        <button type="submit" class="auth-submit-btn">{{ authMode === 'login' ? 'Login' : 'Sign Up' }}</button>
      </form>
    </div>
  </div>

  <!-- New Game Modal -->
  <div v-if="showNewGameModal" class="modal-overlay" @click.self="showNewGameModal = false">
    <div class="modal-content glass time-modal">
      <button class="close-x" @click="showNewGameModal = false">&times;</button>
      <div class="modal-header">
        <h2>New Game</h2>
        <p>Select your time control</p>
      </div>

      <div class="modal-body">
        <div class="time-grid-modal">
          <button 
            v-for="p in timePresets" 
            :key="p.name"
            @click="handleStartGameFromHome(p.time, p.inc)"
            class="preset-btn-modal primary"
          >
            {{ p.name }}
          </button>
          <button @click="showCustomInModal = !showCustomInModal" class="preset-btn-modal primary">
            Custom
          </button>
        </div>

        <div v-if="showCustomInModal" class="custom-settings-modal glass-card">
          <div class="modal-custom-row">
            <div class="modal-custom-field">
              <label>Time(min)</label>
              <input type="number" v-model="customMin" min="0" max="180" class="glass-input compact">
            </div>
            <div class="modal-custom-field">
              <label>inc(sec)</label>
              <input type="number" v-model="customInc" min="0" max="60" class="glass-input compact">
            </div>
          </div>
          <button @click="handleStartGameFromHome(customMin > 0 ? customMin * 60000 : null, customInc * 1000)" class="preset-btn-modal primary full-width">
            Start {{ customMin === 0 ? '(Unlimited)' : `(${customMin}m | ${customInc}s)` }}
          </button>
        </div>
      </div>
    </div>
  </div>

  <!-- Admin Panel Modal Overlay -->
  <AdminPanel v-if="showAdminPanel" :token="currentUser ? currentUser.token : 'admin-token'" @close="showAdminPanel = false" />

  <!-- User Settings Modal -->
  <UserSettings
    v-if="showSettingsModal"
    :settings="userSettings"
    @update:settings="userSettings = $event"
    @close="showSettingsModal = false"
  />
</template>

<style>
:root {
  --bg-color: #1a1a2e;
  --glass-bg: rgba(255, 255, 255, 0.05);
  --glass-border: rgba(255, 255, 255, 0.1);
  --primary: #4ecca3;
  --accent: #f0a500;
}

body {
  margin: 0;
  background: var(--bg-color);
  color: white;
  font-family: 'Inter', sans-serif;
  overflow: hidden;
}

.app-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

.glass {
  background: var(--glass-bg);
  backdrop-filter: blur(10px);
  border: 1px solid var(--glass-border);
}

header {
  padding: 1rem 2rem;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
  position: relative; /* For absolute status-msg */
  z-index: 100;
}

header h1 {
  margin: 0;
  font-size: 1.8rem;
  letter-spacing: 3px;
  color: var(--primary);
  line-height: 1.2;
  font-family: 'Playfair Display', serif;
}

.brand-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.premium-tag {
  font-size: 0.65rem;
  text-transform: uppercase;
  letter-spacing: 2px;
  color: var(--accent);
  opacity: 0.8;
  font-weight: 400;
  margin-left: 2px;
}

.clickable-brand {
  cursor: pointer;
  transition: transform 0.2s, opacity 0.2s;
}
.clickable-brand:hover {
  transform: scale(1.02);
  opacity: 0.9;
}

.nav-tabs {
  display: flex;
  gap: 4px;
  background: rgba(255,255,255,0.04);
  padding: 3px;
  border-radius: 10px;
  border: 1px solid rgba(255,255,255,0.08);
}
.nav-btn {
  padding: 6px 16px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: rgba(255,255,255,0.5);
  font-weight: 600;
  font-size: 0.8rem;
  cursor: pointer;
  transition: all 0.2s;
}
.nav-btn:hover { color: white; background: rgba(255,255,255,0.06); }
.nav-btn.active {
  background: rgba(78,204,163,0.2);
  color: var(--primary);
  box-shadow: 0 2px 8px rgba(78,204,163,0.15);
}


/* ── Layout ───────────────────────────────────────────────────────────── */
.header-left {
  display: flex;
  align-items: center;
  gap: 2rem;
}

.parser-switcher {
  display: flex;
  align-items: center;
  gap: 0.8rem;
  font-size: 0.85rem;
  opacity: 0.8;
}

.parser-switcher label {
  font-weight: 600;
  color: var(--primary);
  text-transform: uppercase;
  letter-spacing: 1px;
}
.opening-badge {
  background: rgba(78, 204, 163, 0.1);
  border: 1px solid rgba(78, 204, 163, 0.3);
  padding: 4px 12px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.85rem;
}
.opening-badge .label {
  color: var(--primary);
  font-weight: 700;
  font-size: 0.75rem;
}
.opening-badge .name {
  color: white;
  font-weight: 600;
}
.glass-select {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid var(--glass-border);
  color: white;
  padding: 4px 8px;
  border-radius: 6px;
  outline: none;
  cursor: pointer;
  transition: all 0.2s;
}

.glass-select:hover {
  background: rgba(255, 255, 255, 0.15);
  border-color: var(--primary);
}

.glass-select option {
  background: #1a1a2e;
  color: white;
}

main {
  flex: 1;
  display: flex;
  padding: 1rem;
  gap: 1rem;
  overflow: hidden;
}

.home-main-container {
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 2rem;
  overflow-y: auto !important;
}

.left-panel, .right-panel {
  width: 320px;
  min-width: 300px;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  overflow-y: auto;
}

.board-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1rem;
}

.player-info {
  width: 100%;
  max-width: 650px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.player-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.p-name {
  font-weight: 700;
  font-size: 1rem;
}

.captured-list {
  display: flex;
  gap: 4px;
  opacity: 0.7;
}

.advantage {
  margin-left: 8px;
  opacity: 0.9;
  font-weight: normal;
}

.clock {
  padding: 8px 16px;
  border-radius: 8px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 1.5rem;
  font-weight: 700;
}

.clock.active {
  background: rgba(78, 204, 163, 0.2);
  border-color: var(--primary);
  color: var(--primary);
}

/* Modal Styles */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.8);
  backdrop-filter: blur(8px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-content {
  width: 100%;
  max-width: 400px;
  padding: 2.5rem;
  border-radius: 30px;
  text-align: center;
  box-shadow: 0 20px 40px rgba(0,0,0,0.4);
  animation: modal-in 0.3s ease-out;
}

@keyframes modal-in {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

.close-x {
  position: absolute;
  top: 1rem;
  right: 1.2rem;
  background: none;
  border: none;
  color: white;
  font-size: 1.8rem;
  cursor: pointer;
  opacity: 0.5;
  transition: opacity 0.2s;
  line-height: 1;
}

.close-x:hover {
  opacity: 1;
}

.modal-header h2 {
  margin: 0 0 1rem 0;
  color: var(--primary);
  letter-spacing: 1px;
}

.modal-body {
  margin-bottom: 2rem;
}

.result-text {
  font-size: 1.4rem;
  font-weight: 700;
  margin-bottom: 0.5rem;
}

.reason-text {
  font-size: 0.9rem;
  opacity: 0.7;
  font-style: italic;
}

.modal-footer {
  display: flex;
  gap: 1rem;
  justify-content: center;
}

.btn {
  padding: 10px 24px;
  border-radius: 8px;
  border: 1px solid var(--glass-border);
  background: var(--glass-bg);
  color: white;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn:hover {
  background: rgba(255, 255, 255, 0.1);
  transform: translateY(-2px);
}

.btn.primary {
  background: var(--primary);
  border-color: var(--primary);
  color: #1a1a2e;
}

.btn.primary:hover {
  background: #3eb88f;
  box-shadow: 0 4px 12px rgba(78, 204, 163, 0.3);
}

/* ── Server Switcher ──────────────────────────────────────────────────── */
.server-switcher {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.server-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  transition: background 0.3s;
}
.server-dot.connected    { background: #4ecca3; box-shadow: 0 0 6px #4ecca3; }
.server-dot.disconnected { background: #e74c3c; box-shadow: 0 0 6px #e74c3c; animation: pulse-red 1s infinite; }

@keyframes pulse-red {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0.4; }
}

.glass-input {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--glass-border);
  border-radius: 10px;
  color: white;
  padding: 10px 14px;
  font-size: 0.9rem;
  transition: all 0.2s;
  outline: none;
  box-sizing: border-box;
}

.glass-input.compact {
  padding: 6px 4px;
  font-size: 0.85rem;
  width: 100%;
}

.glass-input:focus {
  border-color: var(--primary);
  background: rgba(255, 255, 255, 0.08);
}

.server-reset-btn {
  background: none;
  border: 1px solid var(--glass-border);
  color: rgba(255,255,255,0.5);
  border-radius: 6px;
  width: 28px;
  height: 28px;
  cursor: pointer;
  font-size: 1rem;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.server-reset-btn:hover {
  color: var(--primary);
  border-color: var(--primary);
  transform: rotate(-30deg);
}

/* ── Role Selector ────────────────────────────────────────────────────── */
.role-selector {
  display: flex;
  padding: 4px;
  gap: 4px;
}

.glass-pill {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--glass-border);
  border-radius: 30px;
}

.role-btn {
  background: none;
  border: none;
  color: rgba(255, 255, 255, 0.4);
  padding: 6px 14px;
  border-radius: 20px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.8rem;
  font-weight: 600;
  transition: all 0.3s;
}

.role-btn .role-label {
  display: none;
}

.role-btn:hover {
  color: white;
  background: rgba(255, 255, 255, 0.05);
}

.role-btn.active {
  color: #1a1a2e;
  background: white;
  box-shadow: 0 4px 12px rgba(0,0,0,0.2);
}

.role-btn.active.white { background: #fff; color: #1a1a2e; }
.role-btn.active.black { background: #333; color: #fff; border: 1px solid rgba(255,255,255,0.2); }
.role-btn.active.spectator { background: var(--accent); color: #1a1a2e; }

@media (min-width: 1200px) {
  .role-btn .role-label {
    display: inline;
  }
}

.local-mode-badge {
  display: flex;
  align-items: center;
  padding: 6px 16px;
  font-size: 0.85rem;
  font-weight: 600;
  color: #4ecca3;
  letter-spacing: 0.3px;
  white-space: nowrap;
}
.party-mode {
  flex: 0 1 auto;
}

.party-input-group {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}

.party-input {
  width: 150px;
  text-transform: uppercase;
  font-weight: 600;
  letter-spacing: 0.05em;
  text-align: center;
}

.party-active {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.4rem 1rem;
  background: rgba(var(--primary-rgb), 0.15) !important;
  border: 1px solid rgba(var(--primary-rgb), 0.3) !important;
}

.active-label {
  font-size: 0.75rem;
  text-transform: uppercase;
  opacity: 0.7;
  font-weight: 600;
}

.active-code {
  font-family: 'Space Mono', monospace;
  font-weight: 700;
  color: var(--primary);
  font-size: 1.1rem;
  letter-spacing: 0.1em;
}

.leave-btn {
  color: #ff4d4d;
  font-size: 1.2rem;
  padding: 0;
  background: none;
  border: none;
}

.leave-btn:hover {
  color: #ff1a1a;
  transform: scale(1.2);
}

/* Social & Auth Styles */
.user-account {
  margin-left: 2rem;
}

.auth-trigger {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 1.2rem;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.05);
  transition: all 0.2s;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.auth-trigger:hover {
  background: rgba(var(--primary-rgb), 0.1);
  border-color: rgba(var(--primary-rgb), 0.3);
}

.user-profile {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.4rem 1.2rem;
}

.logout-btn {
  font-size: 0.7rem;
  opacity: 0.6;
  background: none;
  border: none;
  color: white;
  cursor: pointer;
  text-decoration: underline;
}

/* Sidebar */
.social-sidebar {
  width: 240px;
  min-width: 240px;
  padding: 1.5rem;
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  height: fit-content;
}

.sidebar-header h3 {
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.2em;
  opacity: 0.6;
  margin: 0;
}

.add-friend-form {
  margin-top: 1rem;
  display: flex;
  gap: 0.5rem;
  align-content: center;
}

.add-friend-form .glass-input.small {
  flex: 1;
  width: auto;
  font-size: 0.75rem;
}

.add-btn {
  background: var(--primary);
  color: black;
  border: none;
  border-radius: 6px;
  width: 35px;
  height: 28px;
  font-weight: 900;
  cursor: pointer;
  transition: transform 0.2s;
}

.add-btn:hover {
  transform: scale(1.1);
  filter: brightness(1.1);
}

.friend-requests {
  margin-top: 1rem;
}

.friend-requests h4 {
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  opacity: 0.4;
  margin-bottom: 0.5rem;
}

.incoming-req {
  border-color: rgba(78, 204, 163, 0.3);
  background: rgba(78, 204, 163, 0.05);
}

.accept-btn {
  font-size: 0.6rem;
  background: var(--primary);
  color: black;
  border: none;
  border-radius: 4px;
  padding: 0.2rem 0.5rem;
  font-weight: 800;
  cursor: pointer;
}



.friend-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.75rem;
  padding: 0.75rem 1rem;
  background: rgba(255, 255, 255, 0.03);
}

.challenge-btn {
  font-size: 0.7rem;
  background: var(--primary);
  color: black;
  border: none;
  border-radius: 6px;
  padding: 0.4rem 0.8rem;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.2s;
}

.challenge-btn:hover {
  transform: translateY(-2px);
  filter: brightness(1.1);
}

.auth-tabs {
  display: flex;
  gap: 1.5rem;
  justify-content: center;
  margin-bottom: 2rem;
}

.auth-submit-btn {
  background: var(--primary);
  color: black;
  border: none;
  padding: 1rem;
  border-radius: 14px;
  font-weight: 700;
  margin-top: 1rem;
  cursor: pointer;
  transition: all 0.2s;
}

.auth-submit-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 20px rgba(var(--primary-rgb), 0.3);
}

.no-friends {
  text-align: center;
  opacity: 0.4;
  font-size: 0.8rem;
  padding: 2rem 0;
}

.time-modal {
  max-width: 400px !important;
}

.time-grid-modal {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
  margin-bottom: 1rem;
}

.preset-btn-modal {
  padding: 15px;
  border-radius: 12px;
  border: 1px solid rgba(78, 204, 163, 0.3);
  background: rgba(78, 204, 163, 0.1);
  color: var(--primary);
  font-weight: 700;
  cursor: pointer;
  transition: all 0.2s;
}

.preset-btn-modal:hover {
  background: rgba(78, 204, 163, 0.25);
  transform: translateY(-2px);
  border-color: var(--primary);
}

.custom-settings-modal {
  margin-top: 1rem;
  padding: 1rem;
  border-radius: 12px;
  background: rgba(255,255,255,0.03);
  display: flex;
  flex-direction: column;
  gap: 1rem;
  width: 100%;
  box-sizing: border-box;
}

.modal-custom-row {
  display: flex !important;
  flex-direction: row !important;
  gap: 0.5rem;
  width: 100%;
  box-sizing: border-box;
}

.modal-custom-field {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  text-align: left;
}

.modal-custom-field label {
  font-size: 0.65rem;
  text-transform: uppercase;
  color: rgba(255,255,255,0.5);
}

.full-width {
  width: 100%;
}

/* ── Settings Trigger ────────────────────────────────────────────────── */
.settings-trigger-btn {
  background: rgba(255,255,255,0.08);
  border: 1px solid rgba(255,255,255,0.12);
  color: rgba(255,255,255,0.6);
  border-radius: 8px;
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1rem;
  cursor: pointer;
  transition: all 0.25s;
}
.settings-trigger-btn:hover {
  background: rgba(78,204,163,0.15);
  border-color: rgba(78,204,163,0.4);
  color: #4ecca3;
  transform: rotate(30deg);
}
.settings-trigger-btn.standalone {
  margin-left: 0.5rem;
}

/* ── Spectator Badge ─────────────────────────────────────────────────── */
.spectator-badge {
  background: rgba(240,165,0,0.18);
  border: 1px solid rgba(240,165,0,0.35);
  color: #f0a500;
  font-size: 0.75rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 20px;
  animation: pulse-badge 2.5s ease-in-out infinite;
  white-space: nowrap;
}

@keyframes pulse-badge {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(240,165,0,0); }
  50%       { opacity: 0.85; box-shadow: 0 0 8px 2px rgba(240,165,0,0.25); }
}

/* ── Emote Popover UI ─────────────────────────────────────────────────── */
.emote-popover-wrapper {
  position: absolute;
  left: 20px;
  bottom: 80px;
  z-index: 100;
}

.emote-trigger-btn {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.4rem;
  cursor: pointer;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  box-shadow: 0 4px 15px rgba(0,0,0,0.3);
  transition: transform 0.2s, background 0.2s;
}

.emote-trigger-btn:hover {
  background: rgba(255,255,255,0.1);
  transform: scale(1.1);
}

.emote-popover {
  position: absolute;
  bottom: 55px;
  left: 0;
  background: rgba(30, 30, 50, 0.85);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 12px;
  padding: 10px;
  width: 200px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.5);
}

.emote-popover-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.emote-pop-btn {
  font-size: 1.4rem;
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 8px;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: transform 0.1s, background 0.1s;
}

.emote-pop-btn:hover {
  background: rgba(255,255,255,0.15);
  transform: scale(1.1);
}

.popover-fade-enter-active, .popover-fade-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}
.popover-fade-enter-from, .popover-fade-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

/* ── Emote Overlay ───────────────────────────────────────────────────── */
.emote-overlay {
  position: fixed;
  right: 0;
  top: 80px;
  bottom: 0;
  width: 80px;
  pointer-events: none;
  z-index: 500;
  overflow: hidden;
}

.emote-stream {
  position: relative;
  width: 100%;
  height: 100%;
}

.emote-float {
  position: absolute;
  right: 12px;
  font-size: 2rem;
  line-height: 1;
  filter: drop-shadow(0 2px 8px rgba(0,0,0,0.5));
  animation: emote-rise 3.5s ease-out forwards;
  /* Distribute vertically by lane (0-4) */
  top: calc(20% + var(--lane, 0) * 12%);
}

@keyframes emote-rise {
  0%   { opacity: 0;   transform: translateX(60px) scale(0.3); }
  12%  { opacity: 1;   transform: translateX(0)    scale(1.2); }
  25%  { opacity: 1;   transform: translateX(0)    scale(1); }
  70%  { opacity: 1;   transform: translateX(0)    translateY(-30px); }
  100% { opacity: 0;   transform: translateX(0)    translateY(-60px) scale(0.8); }
}

.emote-pop-enter-active { animation: emote-rise 3.5s ease-out forwards; }
.emote-pop-leave-active { animation: emote-rise 3.5s ease-out forwards; opacity: 0; }
</style>
