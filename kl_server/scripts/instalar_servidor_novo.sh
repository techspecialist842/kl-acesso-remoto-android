#!/bin/bash
set -euo pipefail

# Instalacao completa do KL em servidor novo (Ubuntu/Debian).
# Uso: bash scripts/instalar_servidor_novo.sh
#
# Servidor recomendado: 4 vCPU / 8 GB RAM / 160 GB disco

REPO_URL="${REPO_URL:-https://github.com/techspecialist842/kl-acesso-remoto-android.git}"
KL_SERVER="${KL_SERVER:-/root/kl_server}"
REPO_DIR="${REPO_DIR:-/root/kl-acesso-remoto-android}"

echo "=========================================="
echo " KL Acesso Remoto - Instalacao servidor novo"
echo "=========================================="

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y git wget curl unzip nginx python3 python3-venv python3-pip \
  openjdk-17-jdk coreutils findutils bash ca-certificates

if [ ! -d "$KL_SERVER" ]; then
  echo "==> Clonando repositorio..."
  git clone "$REPO_URL" /root/kl-temp
  cp -r /root/kl-temp/kl_server "$KL_SERVER"
  cp -r /root/kl-temp "$REPO_DIR"
  rm -rf /root/kl-temp
else
  echo "==> kl_server ja existe, atualizando..."
  bash "$KL_SERVER/scripts/atualizar_arquivos_vps.sh" 2>/dev/null || true
fi

cd "$KL_SERVER"
if [ ! -d "venv" ]; then
  python3 -m venv venv
fi
source venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt

bash scripts/setup_apk_builder.sh
bash scripts/corrigir_nginx_apk.sh

cat >/etc/nginx/sites-available/kl-painel <<'EOF'
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;

    client_max_body_size 20M;

    location / {
        proxy_pass http://127.0.0.1:9001;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 1200s;
        proxy_send_timeout 1200s;
        proxy_read_timeout 1200s;
        proxy_buffering off;
    }
}
EOF

ln -sf /etc/nginx/sites-available/kl-painel /etc/nginx/sites-enabled/kl-painel
rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true
nginx -t
systemctl enable nginx
systemctl reload nginx

pkill -f gunicorn 2>/dev/null || true
cd "$KL_SERVER"
source venv/bin/activate
source apk_builder.env 2>/dev/null || true
nohup gunicorn -c gunicorn.conf.py app:app >/var/log/kl-gunicorn.log 2>&1 &

sleep 2
echo ""
echo "=========================================="
echo " INSTALACAO CONCLUIDA"
echo " Painel: http://$(curl -s ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')"
echo " Log:    tail -f /var/log/kl-gunicorn.log"
echo "=========================================="
