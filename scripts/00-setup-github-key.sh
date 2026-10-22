#!/usr/bin/env bash
set -euo pipefail
KEY=~/.ssh/github_deploy

# read only deploy key so the server can pull but never push
[ -f "$KEY" ] || ssh-keygen -t ed25519 -f "$KEY" -N "" -C "tripsense-vm" >/dev/null
chmod 600 "$KEY"

grep -q "Host github.com" ~/.ssh/config 2>/dev/null || cat >> ~/.ssh/config <<CFG
Host github.com
  IdentityFile $KEY
  IdentitiesOnly yes
CFG
chmod 600 ~/.ssh/config

echo "add this as a READ ONLY deploy key at repo > settings > deploy keys:"
cat "$KEY.pub"
