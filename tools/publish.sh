#!/usr/bin/env bash
# Pushes this project's main branch to its public GitHub repo.
#
# Filen has its own git repo in ~/Projects/Filen (split out of the old shared ~/Projects repo on 2026-11-11 with
# identical commit ids, so GitHub's history continues as a plain fast-forward).
#
# Usage: tools/publish.sh        (commit your changes first)
set -euo pipefail
REMOTE_URL="${FILEN_REMOTE_URL:-https://github.com/nchiker/Filen-Filemanager.git}"

cd "$(dirname "$0")/.."
[ "$(git rev-parse --show-toplevel)" = "$(pwd -P)" ] || { echo "Expected Filen's own git repo here" >&2; exit 1; }

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Uncommitted changes - commit them first (they would not be published):" >&2
  git status --short --untracked-files=no >&2
  exit 1
fi

echo "Publishing $(git rev-list --count main) commits to $REMOTE_URL (main)"
git push "$REMOTE_URL" main:main
