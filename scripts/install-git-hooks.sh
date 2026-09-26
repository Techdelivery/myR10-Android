#!/usr/bin/env bash
# Point git at the version-controlled hooks in .githooks/ instead of .git/hooks/.
#
#   ./scripts/install-git-hooks.sh
#
# Safe to re-run. Uninstall with: git config --unset core.hooksPath
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

git config core.hooksPath .githooks
chmod +x .githooks/*

echo "core.hooksPath -> .githooks"
echo "Installed: $(cd .githooks && ls -1 | tr '\n' ' ')"
