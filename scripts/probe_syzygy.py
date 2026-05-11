#!/usr/bin/env python3
import argparse
import sys


def main() -> int:
    try:
        import chess
        import chess.syzygy
    except Exception:
        return 2

    parser = argparse.ArgumentParser()
    parser.add_argument("--fen", required=True)
    parser.add_argument("--syzygy-dir", required=True)
    args = parser.parse_args()

    board = chess.Board(args.fen)
    if len(board.piece_map()) > 5:
        return 3

    try:
        with chess.syzygy.open_tablebase(args.syzygy_dir) as tb:
            wdl = tb.probe_wdl(board)
            dtz = tb.probe_dtz(board)

            best_move = None
            best_wdl = -999
            best_dtz_abs = 10**9
            for move in board.legal_moves:
                board.push(move)
                try:
                    child_wdl = tb.probe_wdl(board)
                    child_dtz = tb.probe_dtz(board)
                    score = -child_wdl
                    if score > best_wdl or (score == best_wdl and abs(child_dtz) < best_dtz_abs):
                        best_wdl = score
                        best_dtz_abs = abs(child_dtz)
                        best_move = move
                except Exception:
                    pass
                finally:
                    board.pop()

            if best_move is None:
                return 4

            # fen|bestMove|wdl|dtz|dtm
            sys.stdout.write(f"{args.fen}|{best_move.uci()}|{wdl}|{dtz}|\n")
            return 0
    except Exception as ex:
        sys.stderr.write(str(ex))
        return 5


if __name__ == "__main__":
    raise SystemExit(main())
