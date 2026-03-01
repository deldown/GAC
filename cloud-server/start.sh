#!/bin/bash
# GAC Cloud Server Start Script

echo "========================================="
echo "       GAC Cloud Server v2.0"
echo "   Polar-Style ML Anticheat Backend"
echo "========================================="

# Check Python
if ! command -v python3 &> /dev/null; then
    echo "ERROR: Python 3 is required"
    exit 1
fi

# Install dependencies if needed
if [ ! -d "venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv venv
fi

source venv/bin/activate

echo "Installing dependencies..."
pip install -q -r requirements.txt

echo ""
echo "Starting GAC Cloud on port 17865..."
echo "Press Ctrl+C to stop"
echo ""

python main.py
