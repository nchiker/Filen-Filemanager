#!/usr/bin/env bash
# Developer check (not part of the app): what changed in the Filen SDK upstream since our pinned copy, and the state
# of the issues we filed. Run at the start of any Filen work; changes nothing.
#
# Usage: tools/sdk_upstream.sh
set -euo pipefail
cd "$(dirname "$0")/../external/filen-rs"
git fetch -q origin
echo "Pinned:        $(git rev-parse --short HEAD) ($(git log -1 --format=%cs HEAD))"
echo "Upstream main: $(git rev-parse --short origin/main) ($(git log -1 --format=%cs origin/main))"
echo
echo "SDK commits since the pin (filen-sdk-rs, filen-types, filen-macros, filen-mobile-native-cache):"
git log --oneline HEAD..origin/main -- filen-sdk-rs filen-types filen-macros filen-mobile-native-cache | head -40
echo
echo "Our upstream issues (docs/UPSTREAM_REPORTS.md):"
for n in 23 24; do
  gh issue view "$n" -R FilenCloudDienste/filen-rs --json number,state,title,comments \
    --jq '"#\(.number) \(.state) comments=\(.comments|length)  \(.title)"' || echo "#$n: could not read"
done
