#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DIAGRAM_DIR="$ROOT_DIR/diagrams"
OUT_DIR="$ROOT_DIR/out"

mkdir -p "$DIAGRAM_DIR" "$OUT_DIR"

# Render all .puml files from diagrams/ to mirrored .svg files in out/.
files=$(find "$DIAGRAM_DIR" -type f -name '*.puml')

if [ -z "$files" ]; then
  echo "No .puml files found in diagrams/."
  exit 0
fi

echo "$files" | while IFS= read -r file; do
  rel_path=${file#"$DIAGRAM_DIR"/}
  out_file="$OUT_DIR/${rel_path%.puml}.svg"
  out_parent=$(dirname "$out_file")

  mkdir -p "$out_parent"
  plantuml -tsvg -pipe < "$file" > "$out_file"
  echo "generated: ${out_file#"$ROOT_DIR"/}"
done


