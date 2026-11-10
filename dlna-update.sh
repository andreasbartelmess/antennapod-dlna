#!/usr/bin/env bash
# AntennaPod + DLNA: holt das neueste stabile AntennaPod-Release, setzt die DLNA-Commits
# (Branch "dlna") darauf und baut eine signierte Release-APK.
#
#   ./dlna-update.sh            -> auf neuestes Release aktualisieren + bauen
#   ./dlna-update.sh --build    -> nur bauen (ohne Update)
#   ./dlna-update.sh --install  -> zusaetzlich per adb installieren
set -euo pipefail
cd "$(dirname "$0")"

CONF="$HOME/.config/antennapod-dlna"
export JAVA_HOME="${JAVA_HOME_ANTENNAPOD:-$(ls -d "$HOME"/.jdks/jdk-21* | sort -V | tail -1)}"
OUT="$HOME/Projekte/antennapod-dlna-apks"
BUILD_ONLY=0; INSTALL=0
for a in "$@"; do
  case "$a" in
    --build) BUILD_ONLY=1 ;;
    --install) INSTALL=1 ;;
    *) echo "Unbekannte Option: $a"; exit 2 ;;
  esac
done

[ "$(git rev-parse --abbrev-ref HEAD)" = dlna ] || { echo "Bitte auf Branch dlna wechseln"; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "Ungesicherte Aenderungen – erst committen"; exit 1; }

if [ "$BUILD_ONLY" = 0 ]; then
  git fetch --quiet --tags origin
  OLD=$(git describe --tags --abbrev=0 --match '[0-9]*.[0-9]*.[0-9]*' --exclude '*-*' HEAD)
  NEW=$(git tag -l '[0-9]*.[0-9]*.[0-9]*' | grep -v -- - | sort -V | tail -1)
  if [ "$OLD" = "$NEW" ]; then
    echo "Schon auf dem neuesten Release $NEW."
  else
    echo "Update $OLD -> $NEW"
    git tag -f "dlna-vor-$NEW" HEAD >/dev/null
    if ! git rebase --onto "$NEW" "$OLD" dlna; then
      echo
      echo "KONFLIKT beim Rebase. Zurueck zum alten Stand mit:"
      echo "  git rebase --abort"
      exit 1
    fi
  fi
fi

[ -f "$CONF/signing.properties" ] || { echo "Signatur-Konfiguration fehlt: $CONF/signing.properties"; exit 1; }
# shellcheck disable=SC1091
. "$CONF/signing.properties"
./gradlew --console=plain :app:assembleFreeRelease \
  -PreleaseStoreFile="$CONF/release.keystore" \
  -PreleaseStorePassword="$STORE_PASSWORD" \
  -PreleaseKeyAlias=antennapod-dlna \
  -PreleaseKeyPassword="$STORE_PASSWORD"

VERSION=$(git describe --tags --abbrev=0 --match '[0-9]*.[0-9]*.[0-9]*' --exclude '*-*' HEAD)
mkdir -p "$OUT"
APK="$OUT/AntennaPod-$VERSION-dlna.apk"
cp app/build/outputs/apk/free/release/app-free-release.apk "$APK"
echo "Fertig: $APK"
if [ "$INSTALL" = 1 ]; then
  adb install -r "$APK"
fi
