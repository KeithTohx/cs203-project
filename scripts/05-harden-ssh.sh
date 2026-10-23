#!/usr/bin/env bash
set -euo pipefail

# default is 10 concurrent unauthed conns, a brute force flood drops the deploy runner
printf 'MaxStartups 100:30:200\n' | sudo tee /etc/ssh/sshd_config.d/99-hardening.conf >/dev/null

# validate before reload or a typo locks us out of the box
sudo sshd -t
sudo systemctl reload ssh
sudo sshd -T | grep -i maxstartups
