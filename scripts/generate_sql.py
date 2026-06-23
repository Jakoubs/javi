import random
import uuid
import time

users = ["magnus", "hikaru", "fabiano", "beginner", "cheater"]
results = ["white", "black", "draw"]
first_moves = ["e4", "d4", "c4", "Nf3"]
first_move_ucis = ["e2e4", "d2d4", "c2c4", "g1f3"]

sql_lines = []

now_ms = int(time.time() * 1000)
# Generate older games up to a few days ago
day_ms = 24 * 60 * 60 * 1000

for i in range(200): # 200 games total
    game_id = f"test-game-{uuid.uuid4().hex[:8]}"
    white = random.choice(users)
    black = random.choice(users)
    while black == white:
        black = random.choice(users)
        
    result = random.choice(results)
    
    # Random time in the last 7 days
    created_at = now_ms - random.randint(0, 7 * day_ms)
    
    start_fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    final_fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" # dummy
    pgn = "[Event \"Test\"]\n\n"
    
    sql_lines.append(f"INSERT INTO games (id, start_fen, final_fen, pgn, result, created_at, updated_at, white_player, black_player) VALUES ('{game_id}', '{start_fen}', '{final_fen}', '{pgn}', '{result}', {created_at}, {created_at}, '{white}', '{black}');")

    # Add a first move
    move_id = f"test-move-{uuid.uuid4().hex[:8]}"
    idx = random.randint(0, len(first_moves)-1)
    san = first_moves[idx]
    uci = first_move_ucis[idx]
    
    sql_lines.append(f"INSERT INTO move_events (id, game_id, move_number, san, uci, fen_after, timestamp) VALUES ('{move_id}', '{game_id}', 1, '{san}', '{uci}', 'dummy-fen', {created_at + 1000});")

with open("/home/jakobsteiner/Dokumente/fun_projects/Telebot/javi/scripts/seed_analytics.sql", "w") as f:
    f.write("\n".join(sql_lines))

print("SQL seed script generated.")
