#!/usr/bin/env sh
set -e

GRADLE_VERSION="9.2.0"
GRADLE_USER_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_USER_HOME_DIR/wrapper/dists/stackwise-gradle-$GRADLE_VERSION"
GRADLE_HOME="$DIST_DIR/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
ZIP_FILE="$DIST_DIR/gradle-$GRADLE_VERSION-bin.zip"
DIST_URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$DIST_DIR"
  if [ ! -f "$ZIP_FILE" ]; then
    echo "Downloading Gradle $GRADLE_VERSION..."
    if command -v curl >/dev/null 2>&1; then
      curl -L "$DIST_URL" -o "$ZIP_FILE"
    elif command -v wget >/dev/null 2>&1; then
      wget "$DIST_URL" -O "$ZIP_FILE"
    else
      echo "Neither curl nor wget was found. Install Gradle manually or add the official Gradle wrapper jar." >&2
      exit 1
    fi
  fi
  if [ ! -d "$GRADLE_HOME" ]; then
    unzip -q "$ZIP_FILE" -d "$DIST_DIR"
  fi
fi

exec "$GRADLE_BIN" "$@"
