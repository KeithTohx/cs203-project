#!/usr/bin/env bash
set -euo pipefail
MODEL=${MODEL:-qwen2.5:1.5b}
DROPIN=/etc/systemd/system/ollama.service.d

command -v ollama >/dev/null || curl -fsSL https://ollama.com/install.sh | sh

sudo mkdir -p "$DROPIN"
# keep the model resident so the first demo query is not slow
printf '[Service]\nEnvironment="OLLAMA_KEEP_ALIVE=-1"\n' | sudo tee "$DROPIN/override.conf" >/dev/null
# cap ollama so a runaway model gets killed instead of the jvm
printf '[Service]\nMemoryMax=2G\n' | sudo tee "$DROPIN/memory.conf" >/dev/null

sudo systemctl daemon-reload
sudo systemctl restart ollama
sleep 3
ollama pull "$MODEL"
ollama list
