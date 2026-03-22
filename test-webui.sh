#!/bin/bash

echo "Testing WebUI integration..."
echo "Starting web server with shared session..."
echo "WebUI will be available at http://localhost:8080"
echo "Press Ctrl+C to stop"
echo ""

# Start just the web server for testing
sbt "runMain chess.web.WebMain"
