#!/bin/bash

echo "Testing GUI only..."
echo "If you don't see a GUI window, there might be a display issue."
echo ""

sbt "runMain chess.GuiMain"
