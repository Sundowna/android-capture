#!/bin/sh
# One-time setup on a new computer: run from the repo, e.g. `sh tools/install-git-hooks.sh`.
# Hooks are shared by all worktrees of this repo.
hooks="$(git rev-parse --git-common-dir)/hooks"
for name in post-commit post-merge; do
  cp "$(git rev-parse --show-toplevel)/tools/git-hooks/sync-vault-docs.sh" "$hooks/$name"
  chmod +x "$hooks/$name"
done
echo "Installed post-commit and post-merge into $hooks"
