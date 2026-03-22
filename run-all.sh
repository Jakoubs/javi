#!/bin/bash

echo "Starting Chess with all three UIs..."
echo "This will start:"
echo "- TUI: Terminal interface (this terminal)"
echo "- GUI: Desktop window"
echo "- WebUI: http://localhost:8080"
echo ""
echo "All UIs share the same game state!"
echo "Press Ctrl+C to stop all UIs"
echo ""

# Start the unified application
sbt run
