#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# 00 runs before the clone so it is not in this list
for s in 01-install-deps 02-setup-swap 03-install-ollama 04-setup-app 05-harden-ssh 06-setup-fail2ban; do
  echo "=== $s ==="
  ./"$s".sh
done

echo "done, check: curl -s -o /dev/null -w '%{http_code}\n' http://localhost/v3/api-docs"
