# Server setup scripts

Everything needed to rebuild the TripSense server from a bare Ubuntu 24.04 VM.
Each script does one thing and is safe to re-run.

Target: Azure for Students VM, 2 vCPU / 4GB, Ubuntu 24.04, Australia East.

## Before the scripts (portal and github, cannot be scripted)

1. Create the VM: Ubuntu 24.04, `Standard_B2als_v2`, admin user `ubuntu`, ssh public key auth
2. Public IP > Configuration > set a DNS name label
3. Networking > inbound port rules: allow TCP 22 and TCP 80
4. Repo > Settings > Secrets and variables > Actions, add `VM_HOST` and `VM_SSH_KEY`
   (`VM_SSH_KEY` is the laptop-to-VM private key, not the deploy key below)

## Run order

```bash
# 1. deploy key, then add the printed public key to github as a READ ONLY deploy key
curl -fsSL https://raw.githubusercontent.com/KeithTohx/cs203-project/main/scripts/00-setup-github-key.sh | bash

# 2. clone
sudo mkdir -p /opt/tripsense && sudo chown "$USER:$USER" /opt/tripsense
git clone git@github.com:KeithTohx/cs203-project.git /opt/tripsense

# 3. everything else
cd /opt/tripsense/scripts && ./bootstrap.sh
```

`00` has to run before the clone because the clone needs the key it creates.

## What each does

| Script | Does |
|---|---|
| `00-setup-github-key.sh` | Deploy key so the server can pull, prints the public half to add on github |
| `01-install-deps.sh` | apt upgrade, git, openjdk 25, fails if java 25 is not active |
| `02-setup-swap.sh` | 2GB swapfile, fstab entry, swappiness 10 |
| `03-install-ollama.sh` | Ollama, model resident, memory cap, pulls the model |
| `04-setup-app.sh` | Data dir, env file, systemd unit, scoped sudoers, builds and starts |
| `05-harden-ssh.sh` | Raises MaxStartups so an ssh flood cannot drop the deploy runner |
| `06-setup-fail2ban.sh` | Bans repeat ssh offenders |
| `bootstrap.sh` | Runs 01 to 06 in order |

## Checking it worked

```bash
systemctl is-active tripsense ollama fail2ban
sudo fail2ban-client status sshd
sudo sshd -T | grep -i maxstartups     # expect 100:30:200
free -h                                # expect 2.0Gi swap
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/v3/api-docs
```

## Notes

- `MODEL=phi4-mini ./03-install-ollama.sh` overrides the model. We measured phi4-mini at 6 tok/s
  with 130MB headroom vs qwen2.5:1.5b at 21 tok/s with 1.9GB, so the small one is the default
- The sqlite file is at `/var/lib/tripsense/`, deliberately outside the git tree, so a re-clone
  cannot delete it. Back it up before rebuilding a server
- `05` and `06` were added after an ssh brute force put sshd into MaxStartups throttling for 17
  minutes and killed a deploy run
