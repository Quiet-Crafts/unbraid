#!/bin/bash
# Writes app/src/main/assets/notices.txt: every licence the APK must carry. Run after tools/build-libbox.sh (it does).
# Go modules are the ones actually linked into libbox, for the same build tags.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SB=${WORK:-$HOME/.cache/unbraid-libbox}/sing-box
TAGS=$(sed -n 's/.*-tags \([^ ]*\).*/\1/p' "$ROOT/tools/build-libbox.sh")
export GOTOOLCHAIN=go1.25.14 GOFLAGS=-mod=mod
OUT="$ROOT/app/src/main/assets/notices.txt"
mkdir -p "$(dirname "$OUT")"
licence() { ls "$1" | grep -iE '^(LICEN[CS]E|COPYING)' | head -1; }
# A plain Apache-2.0 text is printed once, at the end; a longer file (extra licences appended) is kept whole.
show() { if grep -q "Version 2.0, January 2004" "$1" && [ "$(wc -c < "$1")" -lt 11800 ]; then echo "Licensed under the Apache License, Version 2.0 (full text at the end)."; else cat "$1"; fi; }
deps() { (cd "$SB" && GOOS=android GOARCH=arm64 CGO_ENABLED=1 go list -tags "$TAGS" -deps -f "$1" ./experimental/libbox) | sort -u; }
PKGS=$(mktemp); trap 'rm -f "$PKGS"' EXIT
deps '{{.Dir}}' > "$PKGS" # folders of the packages actually linked
{
  echo "=== unbraid"
  echo "Copyright (C) 2026 the unbraid authors. GNU General Public License v3.0 or later:"
  cat "$ROOT/LICENSE"
  for f in "$ROOT"/tools/notices/*.txt; do [ "${f##*/}" = apache-2.0.txt ] || { echo; cat "$f"; }; done
  echo; echo "=== Go standard library and runtime"; cat "$(cd "$SB" && go env GOROOT)/LICENSE"
  # The Java bindings: gobind's generated code links the gomobile in sing-box's go.mod.
  v=$(awk '$1 == "github.com/sagernet/gomobile" {print $2}' "$SB/go.mod"); d=$(go env GOMODCACHE)/github.com/sagernet/gomobile@$v
  echo; echo "=== github.com/sagernet/gomobile $v (Java bindings)"; cat "$d/$(licence "$d")"
  deps '{{with .Module}}{{.Path}} {{or .Version "-"}} {{.Dir}}{{end}}' | while read -r p v d; do
      [ "$v" = - ] && v= # the main module (sing-box) has no version
      echo; echo "=== $p${v:+ $v}"
      show "$d/$(licence "$d")"
      # NOTICE/COPYRIGHT files, and the licences in subfolders that hold (or sit above) linked code.
      { find "$d" -maxdepth 1 -type f \( -iname 'NOTICE*' -o -iname 'COPYRIGHT*' \)
        find "$d" -mindepth 2 -type f \( -iname 'LICEN[CS]E*' -o -iname 'COPYING*' -o -iname 'NOTICE*' \) ! -name '*.go' ! -path '*/.github/*' ! -path '*/testdata/*'
      } | sort | while read -r n; do grep -q "^${n%/*}\(/\|$\)" "$PKGS" || continue; echo; echo "--- ${n#$d/}"; show "$n"; done
    done
  echo; echo "=== Apache License 2.0"; cat "$ROOT/tools/notices/apache-2.0.txt"
} > "$OUT"
echo "$(grep -c '^=== ' "$OUT") notices, $(wc -c < "$OUT") bytes in ${OUT#$ROOT/}"
