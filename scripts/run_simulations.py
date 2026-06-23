import urllib.request
import urllib.parse
import json
import time
import uuid
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_URL = "http://localhost/api"

def send_command(session_id, command):
    url = f"{BASE_URL}/command?sessionId={session_id}"
    data = json.dumps({"command": command}).encode("utf-8")
    req = urllib.request.Request(
        url, 
        data=data, 
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req) as res:
            return res.read().decode("utf-8")
    except Exception as e:
        print(f"[{session_id}] Command error '{command}': {e}")
        return None

def get_state(session_id, username="guest"):
    url = f"{BASE_URL}/state?sessionId={session_id}&username={urllib.parse.quote(username)}"
    try:
        with urllib.request.urlopen(url) as res:
            return json.loads(res.read().decode("utf-8"))
    except Exception as e:
        print(f"[{session_id}] State error: {e}")
        return None

def run_single_game(game_index, username):
    session_id = f"sim-game-{uuid.uuid4().hex[:8]}"
    print(f"Starting Game #{game_index} for {username} (Session: {session_id})...")
    
    # Initialize game
    send_command(session_id, "new")
    send_command(session_id, "ai w")
    send_command(session_id, "ai b")
    
    # Randomly choose between alphabeta and simple AI for variance
    bot_type = "alphabeta" if game_index % 2 == 0 else "simple"
    send_command(session_id, f"bot {bot_type}")
    
    # Trigger first AI move
    send_command(session_id, "ai")
    
    # Poll until game is complete
    moves_count = 0
    while True:
        time.sleep(2)
        state = get_state(session_id, username)
        if not state:
            continue
        
        status = state.get("status", "Playing")
        moves = state.get("historyMoves", [])
        moves_count = len(moves)
        
        if status != "Playing" and not status.startswith("Check"):
            print(f"Game #{game_index} ({session_id}) completed! Status: {status}, Total Moves: {moves_count}")
            break

def main():
    num_games = 50
    batch_size = 10
    
    if len(sys.argv) > 1:
        try:
            num_games = int(sys.argv[1])
        except ValueError:
            pass
            
    if len(sys.argv) > 2:
        try:
            batch_size = int(sys.argv[2])
        except ValueError:
            pass

    test_users = ["magnus", "hikaru", "fabiano", "beginner", "cheater"]

    print(f"=== Starting Chess Simulation: {num_games} games in batches of {batch_size} ===")
    
    games = list(range(1, num_games + 1))
    
    import random
    
    # Process in batches
    for i in range(0, num_games, batch_size):
        batch = games[i:i + batch_size]
        print(f"\n--- Processing Batch {i // batch_size + 1} (Games {batch[0]} to {batch[-1]}) ---")
        
        with ThreadPoolExecutor(max_workers=batch_size) as executor:
            futures = [executor.submit(run_single_game, g, random.choice(test_users)) for g in batch]
            for future in as_completed(futures):
                future.result()
                
    print("\n=== All simulation games completed successfully! ===")

if __name__ == "__main__":
    main()
