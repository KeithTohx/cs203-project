#!/usr/bin/env bash
set -euo pipefail
APP_DIR=/opt/tripsense
DATA_DIR=/var/lib/tripsense
ME=$(whoami)

# data lives outside the git tree so a pull or re-clone cannot wipe it
sudo mkdir -p "$DATA_DIR"
sudo chown "$ME:$ME" "$DATA_DIR"

# config outside the repo, root only
[ -f /etc/tripsense.env ] || sudo cp "$APP_DIR/.env.example" /etc/tripsense.env
sudo chmod 600 /etc/tripsense.env

sudo cp "$APP_DIR/deploy/tripsense.service" /etc/systemd/system/
sudo systemctl daemon-reload

# deploy workflow may restart this one service and nothing else
echo "$ME ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart tripsense" | sudo tee /etc/sudoers.d/tripsense >/dev/null
sudo chmod 440 /etc/sudoers.d/tripsense

cd "$APP_DIR"
./mvnw -B -q package -DskipTests
sudo systemctl enable --now tripsense
