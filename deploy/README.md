# Deploying TripSense

Azure for Students VM, 2 vCPU / 4GB, Ubuntu 24.04, Australia East.
No containers - the app runs as a systemd service and Ollama runs as its own.

|  |  |
|---|---|
| Public IP | `20.92.233.131` (static) |
| Hostname | `cs203tripsense.australiaeast.cloudapp.azure.com` |
| Admin user | `ubuntu` |
| Repo on VM | `/opt/tripsense` (repo root is the maven project) |
| Database | `/var/lib/tripsense/tripsense.db` |
| Config | `/etc/tripsense.env` (root only, not in git) |
| Swagger | http://cs203tripsense.australiaeast.cloudapp.azure.com/swagger-ui.html |

We picked Azure for Students because it needs no payment card, just an @smu.edu.sg
address. The $100 credit is a runway, not a free tier - roughly to end of December at
this VM size, so the URL is not permanent. Budget alerts are set at 50% and 80%.

## One-time VM setup

SSH in with `ssh -i ~/.ssh/tripsense ubuntu@20.92.233.131`.

### 1. Dependencies and swap

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y git openjdk-25-jdk
java -version                     # must say 25, pom.xml requires it

# 4GB with a model and a jvm is tight, swap stops an OOM kill mid demo
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

### 2. Ollama

```bash
curl -fsSL https://ollama.com/install.sh | sh

# small model - 4GB cannot fit phi4-mini next to the jvm
ollama pull qwen2.5:1.5b
ollama run qwen2.5:1.5b "reply with the single word: ready"

# keep the model resident so the first demo query is not slow
sudo systemctl edit ollama        # add: [Service] / Environment="OLLAMA_KEEP_ALIVE=-1"
sudo systemctl restart ollama
```

Ollama binds `127.0.0.1:11434` only, so the model endpoint is never public.

### 3. Deploy key and clone

A deploy key is an ssh key scoped to one repo. Read-only, so a compromised VM
cannot push, and there is no personal token sitting on the box.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/github_deploy -N "" -C "tripsense-vm"
cat ~/.ssh/github_deploy.pub
```

Add that public key at repo > Settings > Deploy keys > Add deploy key.
Leave **Allow write access unchecked** - the VM only pulls.

```bash
cat >> ~/.ssh/config <<'EOF'
Host github.com
  IdentityFile ~/.ssh/github_deploy
  IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config
ssh -T git@github.com             # expect "successfully authenticated"

sudo mkdir -p /opt/tripsense
sudo chown ubuntu:ubuntu /opt/tripsense
git clone git@github.com:KeithTohx/cs203-project.git /opt/tripsense
```

### 4. Config, data dir, service

```bash
# data lives outside the git tree so a re-clone cannot destroy it
sudo mkdir -p /var/lib/tripsense
sudo chown ubuntu:ubuntu /var/lib/tripsense

# config and any future secrets, root only - see ../.env.example
sudo cp /opt/tripsense/.env.example /etc/tripsense.env
sudo chmod 600 /etc/tripsense.env

sudo cp /opt/tripsense/deploy/tripsense.service /etc/systemd/system/
sudo systemctl daemon-reload

# allow restarting this one service without a password, nothing else
echo 'ubuntu ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart tripsense' \
  | sudo tee /etc/sudoers.d/tripsense
sudo chmod 440 /etc/sudoers.d/tripsense
```

### 5. Build and start

```bash
cd /opt/tripsense
./mvnw -B package                 # tests included on the first run, on purpose
sudo systemctl enable --now tripsense
journalctl -u tripsense -f        # watch for "Started TripSenseApplication"
```

## Checking it

Work outwards, so a failure tells you where the problem is:

```bash
curl -s localhost/v3/api-docs | head -c 200           # on the VM: app healthy
curl http://20.92.233.131/v3/api-docs                 # from a laptop: nsg rule ok
free -h                                              # during an AI call
```

Then the hostname in a browser, and finally from mobile data rather than school
wifi - the rubric wants it reachable off campus.

Persistence check for E7-07: POST something via Swagger, `sudo systemctl restart
tripsense`, GET it back. Then `sudo reboot` and confirm both services return.

## Updating the deployed app

Manual for now:

```bash
cd /opt/tripsense
git pull
./mvnw -B -q package -DskipTests
sudo systemctl restart tripsense
systemctl is-active tripsense
```

A failed build leaves the previous `target/app.jar` in place, so the old version keeps
serving rather than the service going down.

Note there are two separate ssh keys: `~/.ssh/tripsense` on a laptop gets you into the
VM, and `~/.ssh/github_deploy` on the VM lets the VM pull from GitHub.

## Known limits

- plain HTTP, no TLS. Let's Encrypt rate-limits all of `*.cloudapp.azure.com`
  together under `azure.com`, so certs on this hostname are unreliable. DuckDNS plus
  Caddy is the route if a padlock is ever needed
- the Azure credit runs out around end of December and the URL dies with it
- `qwen2.5:1.5b` not `phi4-mini`. We measured both on this box: phi4-mini runs at
  6 tok/s with 130MB headroom and starts swapping, qwen2.5 at 21 tok/s with 1.9GB
  free. A swagger call takes ~2s instead of 25-60s. Change `OLLAMA_MODEL` in
  `/etc/tripsense.env` and restart if you want to revisit, no rebuild needed
- port 80 is bound by a non-root process via `AmbientCapabilities=CAP_NET_BIND_SERVICE`
  in the unit file, so there is no nginx and nothing runs as root
