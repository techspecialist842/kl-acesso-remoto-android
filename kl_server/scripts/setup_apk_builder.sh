#!/bin/bash
set -euo pipefail

# Configuracao do gerador de APK no VPS Linux (Ubuntu/Debian).
# Execute como root: bash scripts/setup_apk_builder.sh

KL_SERVER="${KL_SERVER:-/root/kl_server}"
REPO_DIR="${REPO_DIR:-/root/kl-acesso-remoto-android}"
SDK_DIR="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
REPO_URL="${REPO_URL:-https://github.com/techspecialist842/kl-acesso-remoto-android.git}"

echo "==> KL APK Builder - instalacao"
echo "    kl_server: $KL_SERVER"
echo "    projeto:   $REPO_DIR"
echo "    sdk:       $SDK_DIR"

if [ ! -d "$KL_SERVER" ]; then
  echo "ERRO: pasta kl_server nao encontrada em $KL_SERVER"
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y \
  openjdk-17-jdk unzip wget git ca-certificates \
  coreutils findutils bash

JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
echo "    java:      $JAVA_HOME"

mkdir -p "$SDK_DIR/cmdline-tools"
if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> Baixando Android SDK command-line tools..."
  TMP_ZIP="/tmp/android-cmdline-tools.zip"
  wget -q -O "$TMP_ZIP" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  unzip -qo "$TMP_ZIP" -d "$SDK_DIR/cmdline-tools"
  mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rm -f "$TMP_ZIP"
fi

export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export PATH="$JAVA_HOME/bin:$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"

echo "==> Instalando pacotes Android SDK (pode demorar)..."
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

if [ ! -f "$REPO_DIR/gradlew" ]; then
  echo "==> Clonando projeto Android..."
  git clone "$REPO_URL" "$REPO_DIR"
else
  echo "==> Atualizando projeto Android..."
  git -C "$REPO_DIR" pull origin main || true
fi
chmod +x "$REPO_DIR/gradlew"

echo "==> Instalando dependencias Python..."
cd "$KL_SERVER"
if [ -d "venv" ]; then
  source venv/bin/activate
elif [ -d "venv_win" ]; then
  echo "AVISO: venv_win detectado; use venv Linux no VPS."
  python3 -m venv venv
  source venv/bin/activate
else
  python3 -m venv venv
  source venv/bin/activate
fi
pip install --upgrade pip
pip install -r requirements.txt

ENV_FILE="$KL_SERVER/apk_builder.env"
cat > "$ENV_FILE" <<EOF
JAVA_HOME=$JAVA_HOME
ANDROID_SDK_ROOT=$SDK_DIR
ANDROID_HOME=$SDK_DIR
ANDROID_PROJECT_PATH=$REPO_DIR
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$JAVA_HOME/bin:$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:\$PATH
EOF

echo "==> Verificando ambiente..."
cd "$KL_SERVER"
set -a
source "$ENV_FILE"
set +a
python - <<'PY'
import json
from services.apk_builder import verificar_ambiente_build
print(json.dumps(verificar_ambiente_build(), indent=2, ensure_ascii=False))
PY

echo ""
echo "OK: configuracao salva em $ENV_FILE"
echo "Reinicie o gunicorn:"
echo "  cd $KL_SERVER && source venv/bin/activate && source apk_builder.env"
echo "  pkill -f gunicorn || true"
echo "  gunicorn -c gunicorn.conf.py app:app"
