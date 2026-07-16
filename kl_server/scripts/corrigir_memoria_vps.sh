#!/bin/bash
set -euo pipefail

# Adiciona swap se o VPS tiver pouca RAM (Gradle precisa de memoria).
# Uso: bash scripts/corrigir_memoria_vps.sh

SWAP_FILE="${SWAP_FILE:-/swapfile}"
SWAP_MB="${SWAP_MB:-4096}"

if swapon --show | grep -q "$SWAP_FILE"; then
  echo "OK: swap ja ativo em $SWAP_FILE"
  free -h
  exit 0
fi

if [ -f "$SWAP_FILE" ]; then
  chmod 600 "$SWAP_FILE"
  mkswap "$SWAP_FILE" >/dev/null 2>&1 || true
  swapon "$SWAP_FILE" || true
else
  echo "==> Criando swap ${SWAP_MB}MB em $SWAP_FILE ..."
  fallocate -l "${SWAP_MB}M" "$SWAP_FILE" 2>/dev/null || dd if=/dev/zero of="$SWAP_FILE" bs=1M count="$SWAP_MB" status=progress
  chmod 600 "$SWAP_FILE"
  mkswap "$SWAP_FILE"
  swapon "$SWAP_FILE"
fi

if ! grep -q "$SWAP_FILE" /etc/fstab 2>/dev/null; then
  echo "$SWAP_FILE none swap sw 0 0" >> /etc/fstab
fi

echo "OK: memoria apos swap:"
free -h
