#!/usr/bin/env bash
set -euo pipefail

# we build on the server so we need the full jdk not a jre
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get upgrade -y -qq
sudo apt-get install -y -qq git openjdk-25-jdk

# fail loudly on the wrong java, pom requires 25
java -version 2>&1 | grep -q '"25' || { echo "java 25 not active"; exit 1; }
java -version
