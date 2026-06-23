import urllib.request
import urllib.parse
import json
import time
import uuid
import random

BASE_URL = "http://localhost/api"
USERS = ["magnus", "hikaru", "fabiano", "beginner", "cheater"]

moves = ["e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7"]

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

def set_user(session_id, username):
    url = f"{BASE_URL}/state?sessionId={session_id}&username={urllib.parse.quote(username)}"
    try:
        with urllib.request.urlopen(url) as res:
            pass
    except Exception as e:
        print(f"[{session_id}] State error: {e}")

def run_game(game_index):
    session_id = f"fast-game-{uuid.uuid4().hex[:8]}"
    username = random.choice(USERS)
    print(f"Starting Game #{game_index} for {username} (Session: {session_id})...")
    
    # Initialize game
    send_command(session_id, "new")
    
    # Set the user
    set_user(session_id, username)
    
    # Apply moves
    for move in moves:
        send_command(session_id, move)

    print(f"Game #{game_index} ({session_id}) completed!")

def main():
    print("=== Starting Fast Chess Simulation ===")
    for i in range(1, 101):
        run_game(i)
    print("=== All games completed successfully! ===")

if __name__ == "__main__":
    main()
