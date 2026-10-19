#!/bin/sh
# Disable every package in debloat-list.txt, one pm call per shell session.
# Packages listed in never-disable.txt are always skipped.
cd "$(dirname "$0")"
grep -vE '^\s*(#|$)' debloat-list.txt | while read -r p; do
  if grep -qx "$p" never-disable.txt; then echo "SKIP (never-disable) $p"; continue; fi
  printf '%-45s ' "$p"; ./tv shell "pm disable-user --user 0 $p 2>&1" | tr -d '\n'; echo
done
