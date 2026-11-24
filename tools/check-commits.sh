#!/usr/bin/env bash
# Every commit is authored by ExcuseMi and its message names no AI tool and has no em-dash.
# This file holds the guard's own patterns (PrivateReferencesTest does not scan .sh files).
set -euo pipefail
DASH=$(printf '\342\200\224')
if git log --format='%an <%ae>%n%B' | grep -nE "Co-Authored-By|Generated with|Claude|claude\.ai|$DASH"; then
  echo "A commit message names an AI tool or has an em-dash"; exit 1
fi
if git log --format='%an' | grep -vx 'ExcuseMi'; then echo "A commit is not authored by ExcuseMi"; exit 1; fi
echo "commits ok"
