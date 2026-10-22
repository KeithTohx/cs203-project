#!/usr/bin/env bash
set -euo pipefail
SWAPFILE=/swapfile

# 4gb box runs a model and a jvm, swap turns an oom kill into slowness
if [ ! -f "$SWAPFILE" ]; then
  sudo fallocate -l 2G "$SWAPFILE"
  sudo chmod 600 "$SWAPFILE"
  sudo mkswap "$SWAPFILE" >/dev/null
  sudo swapon "$SWAPFILE"
fi
grep -q "^$SWAPFILE" /etc/fstab || echo "$SWAPFILE none swap sw 0 0" | sudo tee -a /etc/fstab >/dev/null

# emergency only, keeps the model in ram instead of paging it out
echo "vm.swappiness=10" | sudo tee /etc/sysctl.d/99-swappiness.conf >/dev/null
sudo sysctl -q -w vm.swappiness=10
free -h
