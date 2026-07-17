#!/bin/bash
set -euo pipefail

# Atualiza arquivos do kl_server no VPS SEM precisar de git pull.
# Use quando /root/kl_server foi copiado manualmente (sem .git).
#
# Uso: bash scripts/atualizar_arquivos_vps.sh

KL_SERVER="${KL_SERVER:-/root/kl_server}"
BASE_URL="${BASE_URL:-https://raw.githubusercontent.com/techspecialist842/kl-acesso-remoto-android/main/kl_server}"

if [ ! -d "$KL_SERVER" ]; then
  echo "ERRO: pasta nao encontrada: $KL_SERVER"
  exit 1
fi

cd "$KL_SERVER"
mkdir -p scripts services routes templates data/apks data/apk_icons

baixar() {
  local destino="$1"
  local url="$2"
  echo "==> $destino"
  wget -q -O "$destino" "$url"
}

baixar "gunicorn.conf.py"              "$BASE_URL/gunicorn.conf.py"
baixar "requirements.txt"              "$BASE_URL/requirements.txt"
baixar "app.py"                        "$BASE_URL/app.py"
baixar "scripts/setup_apk_builder.sh"  "$BASE_URL/scripts/setup_apk_builder.sh"
baixar "scripts/compilar_apk_worker.py" "$BASE_URL/scripts/compilar_apk_worker.py"
baixar "scripts/corrigir_nginx_apk.sh"  "$BASE_URL/scripts/corrigir_nginx_apk.sh"
baixar "scripts/corrigir_gradle_vps.sh"  "$BASE_URL/scripts/corrigir_gradle_vps.sh"
baixar "scripts/instalar_servidor_novo.sh" "$BASE_URL/scripts/instalar_servidor_novo.sh"
baixar "scripts/atualizar_arquivos_vps.sh" "$BASE_URL/scripts/atualizar_arquivos_vps.sh"
baixar "services/apk_builder.py"       "$BASE_URL/services/apk_builder.py"
baixar "services/apk_jobs.py"          "$BASE_URL/services/apk_jobs.py"
baixar "routes/apk.py"                   "$BASE_URL/routes/apk.py"
baixar "routes/auth.py"                   "$BASE_URL/routes/auth.py"
baixar "routes/usuarios.py"               "$BASE_URL/routes/usuarios.py"
baixar "services/auth.py"                 "$BASE_URL/services/auth.py"
baixar "services/usuarios.py"             "$BASE_URL/services/usuarios.py"
baixar "templates/login.html"             "$BASE_URL/templates/login.html"
baixar "templates/usuarios.html"          "$BASE_URL/templates/usuarios.html"
baixar "services/branding.py"            "$BASE_URL/services/branding.py"
baixar "templates/gerar_apk.html"        "$BASE_URL/templates/gerar_apk.html"
baixar "templates/personalizar.html"     "$BASE_URL/templates/personalizar.html"
baixar "templates/painel.html"           "$BASE_URL/templates/painel.html"
baixar "templates/dispositivo.html"      "$BASE_URL/templates/dispositivo.html"

chmod +x scripts/setup_apk_builder.sh
chmod +x scripts/corrigir_nginx_apk.sh
chmod +x scripts/corrigir_gradle_vps.sh
chmod +x scripts/instalar_servidor_novo.sh
chmod +x scripts/atualizar_arquivos_vps.sh
chmod +x scripts/compilar_apk_worker.py

echo ""
echo "OK: arquivos atualizados em $KL_SERVER"
echo "Proximo passo:"
echo "  bash scripts/setup_apk_builder.sh"
