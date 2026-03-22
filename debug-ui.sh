#!/bin/bash

echo "Debugging UI startup..."
echo "Testing GUI separately first..."
echo ""

# Test GUI alone
echo "=== Testing GUI only ==="
timeout 10 sbt "runMain chess.GuiMain" 2>&1 | head -20

echo ""
echo "=== Testing WebUI only ==="
timeout 10 sbt "runMain chess.web.WebMain" 2>&1 | head -20

echo ""
echo "=== Testing unified mode ==="
timeout 15 sbt run 2>&1 | head -30
