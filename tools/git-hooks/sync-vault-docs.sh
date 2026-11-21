#!/bin/sh
# Installed as .git/hooks/post-commit and .git/hooks/post-merge by tools/install-git-hooks.sh.
# Keeps the vault copy of FEATURES.md, FLOWS.md and the screens current. Never blocks Git.
root="$(git rev-parse --show-toplevel)" || exit 0
for py in python py python3; do
  if command -v "$py" >/dev/null 2>&1; then
    "$py" -I "$root/tools/sync-vault-docs.py"
    exit 0
  fi
done
echo "sync-vault-docs: no Python found, skipped"
exit 0
