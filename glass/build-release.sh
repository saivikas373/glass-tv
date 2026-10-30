#!/usr/bin/env bash
# build-release.sh - build the signed public release: ../setup/dist/Glass.apk
# The keystore is outside the kit (~/glass-release-key/) and its password is read from the macOS Keychain.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export GLASS_KEYSTORE="$HOME/glass-release-key/glass-release.jks"
GLASS_KEYSTORE_PASSWORD="$(security find-generic-password -s "Glass TV release key" -w)"; export GLASS_KEYSTORE_PASSWORD
cp ../setup/debloat-list.txt app/src/main/assets/debloat-list.txt   # one list for the scripts and the app
./gradlew --no-daemon -q clean assembleRelease
mkdir -p ../setup/dist
cp app/build/outputs/apk/release/app-release.apk ../setup/dist/Glass.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs ../setup/dist/Glass.apk | grep -E "Signer #1 certificate (DN|SHA-256)"
shasum -a 256 ../setup/dist/Glass.apk
