#!/usr/bin/env bash
set -euo pipefail

sudo apt-get install -y -qq fail2ban

# backend systemd because ubuntu 24.04 cloud images have no /var/log/auth.log
sudo tee /etc/fail2ban/jail.local >/dev/null <<'CFG'
[sshd]
enabled  = true
backend  = systemd
maxretry = 3
findtime = 10m
bantime  = 1h
CFG

sudo systemctl enable --now fail2ban
sudo systemctl restart fail2ban
sleep 2
sudo fail2ban-client status sshd
