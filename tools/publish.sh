#!/usr/bin/env bash
# Pushes this project (and ONLY this project) to its public GitHub repo.
#
# Filen/ lives inside a larger local repo (~/Projects) next to unrelated projects. `git subtree split` extracts
# just this folder's history into a branch; the split is deterministic, so every run produces the same commit ids
# for already-published commits and the push is a plain fast-forward.
#
# Usage: tools/publish.sh        (commit your changes in Filen/ first)
set -euo pipefail
REMOTE_URL="${FILEN_REMOTE_URL:-https://github.com/nchiker/Filen-Filemanager.git}"
BRANCH=filen-only

cd "$(dirname "$0")/.."
PREFIX="$(git rev-parse --show-prefix)"; PREFIX="${PREFIX%/}"
[ -n "$PREFIX" ] || { echo "This must run from a project folder inside a larger repo" >&2; exit 1; }
cd "$(git rev-parse --show-toplevel)"

if [ -n "$(git status --porcelain --untracked-files=no -- "$PREFIX")" ]; then
  echo "Uncommitted changes in $PREFIX/ - commit them first (they would not be published):" >&2
  git status --short --untracked-files=no -- "$PREFIX" >&2
  exit 1
fi

git subtree split --prefix="$PREFIX" -b "$BRANCH" >/dev/null 2>&1
echo "Publishing $(git rev-list --count "$BRANCH") commits of $PREFIX/ to $REMOTE_URL (main)"
git push "$REMOTE_URL" "$BRANCH:main"
