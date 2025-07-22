#!/bin/bash

# Installation script for find-ref-svelte
# This script installs the Groovy script as a system-wide executable

set -e

SCRIPT_NAME="find-ref-svelte"
SCRIPT_FILE="find-ref-svelte.groovy"
INSTALL_DIR="/usr/local/bin"
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Installing $SCRIPT_NAME..."

# Check if Groovy is installed
if ! command -v groovy &> /dev/null; then
    echo "Error: Groovy is not installed or not in PATH"
    echo "Please install Groovy first: https://groovy-lang.org/install.html"
    echo "On macOS: brew install groovy"
    exit 1
fi

# Check if script file exists
if [ ! -f "$CURRENT_DIR/$SCRIPT_FILE" ]; then
    echo "Error: $SCRIPT_FILE not found in current directory"
    exit 1
fi

# Check if install directory exists and is writable
if [ ! -d "$INSTALL_DIR" ]; then
    echo "Creating $INSTALL_DIR directory..."
    sudo mkdir -p "$INSTALL_DIR"
fi

if [ ! -w "$INSTALL_DIR" ]; then
    echo "Note: $INSTALL_DIR is not writable, will use sudo for installation"
    USE_SUDO=true
else
    USE_SUDO=false
fi

# Backup existing installation if it exists
if [ -L "$INSTALL_DIR/$SCRIPT_NAME" ] || [ -f "$INSTALL_DIR/$SCRIPT_NAME" ]; then
    echo "Backing up existing installation..."
    if [ "$USE_SUDO" = true ]; then
        sudo mv "$INSTALL_DIR/$SCRIPT_NAME" "$INSTALL_DIR/$SCRIPT_NAME.backup.$(date +%Y%m%d_%H%M%S)"
    else
        mv "$INSTALL_DIR/$SCRIPT_NAME" "$INSTALL_DIR/$SCRIPT_NAME.backup.$(date +%Y%m%d_%H%M%S)"
    fi
fi

# Create symbolic link
echo "Creating symbolic link in $INSTALL_DIR..."
if [ "$USE_SUDO" = true ]; then
    sudo ln -sf "$CURRENT_DIR/$SCRIPT_FILE" "$INSTALL_DIR/$SCRIPT_NAME"
else
    ln -sf "$CURRENT_DIR/$SCRIPT_FILE" "$INSTALL_DIR/$SCRIPT_NAME"
fi

# Verify installation
if [ -L "$INSTALL_DIR/$SCRIPT_NAME" ]; then
    echo "✅ Installation successful!"
    echo "Symbolic link created: $INSTALL_DIR/$SCRIPT_NAME -> $CURRENT_DIR/$SCRIPT_FILE"
    
    # Test if the command is accessible
    if command -v "$SCRIPT_NAME" &> /dev/null; then
        echo "✅ Command '$SCRIPT_NAME' is now available in your PATH"
        
        # Test basic functionality
        echo "Testing installation..."
        if "$SCRIPT_NAME" --version &> /dev/null; then
            echo "✅ Installation test passed"
        else
            echo "⚠️  Installation test failed - the command exists but may have issues"
        fi
    else
        echo "⚠️  Command '$SCRIPT_NAME' is not in your PATH"
        echo "You may need to restart your terminal or add $INSTALL_DIR to your PATH"
    fi
else
    echo "❌ Installation failed - symbolic link was not created"
    exit 1
fi

echo ""
echo "Installation complete! You can now use '$SCRIPT_NAME' from anywhere."
echo ""
echo "Usage examples:"
echo "  $SCRIPT_NAME /path/to/svelte/project"
echo "  $SCRIPT_NAME --help"
echo "  $SCRIPT_NAME -v /path/to/file.svelte"
echo "  $SCRIPT_NAME -f json -o output.json /path/to/project"
echo ""
echo "To uninstall, run: sudo rm $INSTALL_DIR/$SCRIPT_NAME"