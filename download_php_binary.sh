#!/bin/bash
# ============================================================================
# download_php_binary.sh
# Downloads a precompiled php-cgi binary for ARM64 Android from Termux packages.
# Run this script from the project root before building the APK.
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSET_DIR="$SCRIPT_DIR/app/src/main/assets/bin/php/arm64"
PHP_CGI="$ASSET_DIR/php-cgi"

echo "=========================================="
echo "  Laragon Android - PHP Binary Downloader"
echo "=========================================="
echo ""

# Create asset directory
mkdir -p "$ASSET_DIR"

# Check if binary already exists
if [ -f "$PHP_CGI" ]; then
    echo "[INFO] php-cgi binary already exists at: $PHP_CGI"
    echo "       Delete it and re-run to download a fresh copy."
    exit 0
fi

echo "[INFO] Downloading php-cgi for ARM64 from Termux packages..."
echo ""

# Termux PHP packages are distributed as .deb files.
# We need to extract the php-cgi binary from the package.
# The URL pattern for Termux packages:
# https://packages.termux.dev/apt/termux-main/pool/main/p/php/

# Try to get the latest PHP package listing
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# Download the Termux PHP package
# Using a known working version - PHP 8.2.x
TERMUX_REPO="https://packages.termux.dev/apt/termux-main"

# Method 1: Try to download from Termux package repository directly
echo "[INFO] Attempting to download from Termux repository..."

# Fetch package index to find the latest PHP version
echo "[INFO] Fetching package list..."
PHP_PKG_URL=$(curl -sL "$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages" 2>/dev/null | \
    grep -A 20 "^Package: php$" | grep "Filename:" | head -1 | awk '{print $2}')

if [ -z "$PHP_PKG_URL" ]; then
    # Fallback: try a known package path
    echo "[WARN] Could not auto-detect PHP package URL."
    echo "[INFO] Trying alternative download method..."

    # Alternative: Build from Termux source
    echo ""
    echo "========================================"
    echo "  MANUAL INSTRUCTIONS"
    echo "========================================"
    echo ""
    echo "Option 1: Download from Termux on your device"
    echo "  1. Install Termux from F-Droid on your Android device"
    echo "  2. Run: pkg install php"
    echo "  3. Copy the php-cgi binary from /data/data/com.termux/files/usr/bin/php-cgi"
    echo "  4. Place it at: $ASSET_DIR/php-cgi"
    echo ""
    echo "Option 2: Build from Termux packages source"
    echo "  1. Clone: https://github.com/termux/termux-packages"
    echo "  2. Follow build instructions for aarch64"
    echo "  3. Copy the resulting php-cgi binary"
    echo ""
    echo "Option 3: Use a pre-built binary"
    echo "  1. Visit: https://github.com/termux/termux-packages"
    echo "  2. Download the php .deb package for aarch64"
    echo "  3. Extract: ar x php_*.deb && tar xf data.tar.xz"
    echo "  4. Copy ./data/data/com.termux/files/usr/bin/php-cgi"
    echo "     to: $ASSET_DIR/php-cgi"
    echo ""
    echo "After placing the binary, run:"
    echo "  chmod +x $ASSET_DIR/php-cgi"
    echo ""
    exit 1
fi

FULL_URL="$TERMUX_REPO/$PHP_PKG_URL"
echo "[INFO] Downloading PHP package from: $FULL_URL"

# Download the .deb package
DEB_FILE="$TEMP_DIR/php.deb"
curl -L -o "$DEB_FILE" "$FULL_URL"

if [ ! -f "$DEB_FILE" ] || [ ! -s "$DEB_FILE" ]; then
    echo "[ERROR] Failed to download PHP package."
    exit 1
fi

echo "[INFO] Extracting php-cgi binary from package..."

# Extract the .deb file (ar archive)
cd "$TEMP_DIR"
ar x "$DEB_FILE" 2>/dev/null || {
    echo "[ERROR] Failed to extract .deb file. Install 'binutils' (ar command)."
    exit 1
}

# Extract data.tar
if [ -f "data.tar.xz" ]; then
    tar xf data.tar.xz
elif [ -f "data.tar.gz" ]; then
    tar xzf data.tar.gz
elif [ -f "data.tar.bz2" ]; then
    tar xjf data.tar.bz2
else
    echo "[ERROR] No data.tar found in .deb package."
    exit 1
fi

# Find php-cgi binary
PHP_CGI_BIN=$(find . -name "php-cgi" -type f | head -1)

if [ -z "$PHP_CGI_BIN" ]; then
    echo "[ERROR] php-cgi binary not found in the package."
    echo "[INFO] The php package might not include php-cgi."
    echo "[INFO] Try installing 'php-cli' or building from source."
    exit 1
fi

# Copy to asset directory
cp "$PHP_CGI_BIN" "$PHP_CGI"
chmod +x "$PHP_CGI"

echo ""
echo "[SUCCESS] php-cgi binary installed at: $PHP_CGI"
echo "[INFO] Binary size: $(du -h "$PHP_CGI" | cut -f1)"

# Verify it can run (won't work on x86 host, but check file format)
FILE_TYPE=$(file "$PHP_CGI" 2>/dev/null || echo "unknown")
echo "[INFO] File type: $FILE_TYPE"

echo ""
echo "[DONE] You can now build the APK with the embedded PHP binary."
