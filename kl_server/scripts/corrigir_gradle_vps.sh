#!/bin/bash
set -euo pipefail

# Corrige erro: uname not found / xargs is not available
# Uso: bash scripts/corrigir_gradle_vps.sh

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y coreutils findutils bash

KL_SERVER="${KL_SERVER:-/root/kl_server}"
ENV_FILE="$KL_SERVER/apk_builder.env"

if [ -f "$ENV_FILE" ]; then
  if ! grep -q "/usr/bin" "$ENV_FILE"; then
    sed -i 's|^PATH=|PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:|' "$ENV_FILE"
    echo "==> PATH atualizado em $ENV_FILE"
  else
    echo "==> PATH ja contem /usr/bin"
  fi
else
  echo "AVISO: $ENV_FILE nao encontrado. Rode setup_apk_builder.sh primeiro."
fi

echo "OK: uname=$(command -v uname) xargs=$(command -v xargs)"

REPO_DIR="${REPO_DIR:-/root/kl-acesso-remoto-android}"
if [ -f "$REPO_DIR/gradlew" ]; then
  echo "==> Parando daemons Gradle e removendo JDK 21 auto-baixado..."
  cd "$REPO_DIR"
  ./gradlew --stop >/dev/null 2>&1 || true
  rm -f "$REPO_DIR/gradle/gradle-daemon-jvm.properties"
  rm -rf /root/.gradle/daemon /root/.gradle/jdks 2>/dev/null || true
fi

echo "Reinicie o gunicorn e tente gerar o APK novamente."
