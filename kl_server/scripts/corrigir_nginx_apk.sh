#!/bin/bash
set -euo pipefail

# Ajusta nginx para gerador de APK (timeout + upload de icone).
# Uso: bash scripts/corrigir_nginx_apk.sh

echo "==> KL - corrigindo nginx para APK"

GLOBAL_CONF="/etc/nginx/conf.d/kl-apk-timeouts.conf"
cat > "$GLOBAL_CONF" <<'EOF'
# KL_APK_TIMEOUT - gerador de APK (http level)
client_max_body_size 20M;
client_body_timeout 1200s;
proxy_connect_timeout 1200s;
proxy_send_timeout 1200s;
proxy_read_timeout 1200s;
send_timeout 1200s;
proxy_buffering off;
EOF
echo "==> Criado $GLOBAL_CONF"

patch_arquivo() {
  local arquivo="$1"
  [ -f "$arquivo" ] || return 0
  if grep -q "KL_APK_LOCATION" "$arquivo"; then
    echo "    ja corrigido: $arquivo"
    return 0
  fi
  if ! grep -q "proxy_pass" "$arquivo"; then
    echo "    sem proxy_pass: $arquivo"
    return 0
  fi
  cp "$arquivo" "${arquivo}.bak_kl_apk"
  awk '
    /proxy_pass/ && !feito {
      print $0
      print "        # KL_APK_LOCATION"
      print "        proxy_connect_timeout 1200s;"
      print "        proxy_send_timeout 1200s;"
      print "        proxy_read_timeout 1200s;"
      print "        proxy_buffering off;"
      print "        client_max_body_size 20M;"
      feito=1
      next
    }
    { print }
  ' "${arquivo}.bak_kl_apk" > "$arquivo"
  echo "    corrigido: $arquivo"
}

echo "==> Corrigindo sites ativos..."
shopt -s nullglob
for arquivo in /etc/nginx/sites-enabled/*; do
  patch_arquivo "$arquivo"
done

for arquivo in /etc/nginx/sites-available/*; do
  patch_arquivo "$arquivo"
done

if ! grep -q 'include /etc/nginx/conf.d/\*\.conf' /etc/nginx/nginx.conf 2>/dev/null; then
  echo "AVISO: conf.d pode nao estar incluido em nginx.conf"
fi

nginx -t
systemctl reload nginx

echo ""
echo "OK: nginx recarregado com timeouts de 1200s"
echo "Teste: curl -I https://servidorpremium-kl.lat/api/gerar-apk/verificar"
