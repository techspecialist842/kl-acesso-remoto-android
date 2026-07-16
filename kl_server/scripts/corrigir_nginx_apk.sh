#!/bin/bash
set -euo pipefail

# Ajusta nginx para APK: timeout longo + upload de icone.
# Uso: bash scripts/corrigir_nginx_apk.sh

CONF=""
for candidato in \
  /etc/nginx/sites-available/default \
  /etc/nginx/sites-available/servidorpremium-kl.lat \
  /etc/nginx/conf.d/default.conf \
  /etc/nginx/nginx.conf
do
  if [ -f "$candidato" ]; then
    CONF="$candidato"
    break
  fi
done

if [ -z "$CONF" ]; then
  echo "ERRO: arquivo nginx nao encontrado"
  exit 1
fi

echo "==> Usando: $CONF"

if ! grep -q "KL_APK_TIMEOUT" "$CONF"; then
  cat >> "$CONF" <<'EOF'

# KL_APK_TIMEOUT - gerador de APK
client_max_body_size 20M;
proxy_connect_timeout 1200s;
proxy_send_timeout    1200s;
proxy_read_timeout    1200s;
send_timeout          1200s;
EOF
  echo "==> Regras adicionadas"
else
  echo "==> Regras ja existem"
fi

nginx -t
systemctl reload nginx
echo "OK: nginx recarregado"
