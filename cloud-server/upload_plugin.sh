#!/bin/bash
# GAC Plugin Upload Script
# Uploads a new JAR to the cloud server for auto-update

# Configuration
CLOUD_URL="${GAC_CLOUD_URL:-http://localhost:17865}"
ADMIN_KEY="${GAC_ADMIN_KEY:-gac-admin-secret}"
JAR_PATH="${1:-../target/gac-1.0-SNAPSHOT.jar}"
VERSION="${2:-1.0.0}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}GAC Plugin Uploader${NC}"
echo "===================="
echo "Cloud URL: $CLOUD_URL"
echo "JAR Path: $JAR_PATH"
echo "Version: $VERSION"
echo ""

# Check if JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${RED}ERROR: JAR file not found: $JAR_PATH${NC}"
    echo "Usage: ./upload_plugin.sh [jar_path] [version]"
    echo "Example: ./upload_plugin.sh ../target/gac-1.0-SNAPSHOT.jar 1.0.1"
    exit 1
fi

# Get file size
SIZE=$(stat -f%z "$JAR_PATH" 2>/dev/null || stat -c%s "$JAR_PATH" 2>/dev/null)
echo "JAR Size: $SIZE bytes"
echo ""

# Upload
echo -e "${YELLOW}Uploading...${NC}"
RESPONSE=$(curl -s -X POST "$CLOUD_URL/api/v2/update/upload" \
    -H "X-Admin-Key: $ADMIN_KEY" \
    -H "X-Plugin-Version: $VERSION" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$JAR_PATH")

# Check response
if echo "$RESPONSE" | grep -q '"success":true'; then
    echo -e "${GREEN}SUCCESS!${NC}"
    echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"
else
    echo -e "${RED}FAILED!${NC}"
    echo "$RESPONSE"
    exit 1
fi

echo ""
echo -e "${GREEN}Plugin v$VERSION uploaded successfully!${NC}"
echo "Servers can now update with: /gac update"
