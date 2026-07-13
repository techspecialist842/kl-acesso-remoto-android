import os
import re
import shutil
import subprocess
import tempfile
import time
import uuid
from xml.sax.saxutils import escape

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK_OUTPUT_DIR = os.path.join(BASE_DIR, "data", "apks")
ICON_UPLOAD_DIR = os.path.join(BASE_DIR, "data", "apk_icons")

IGNORE_DIRS = {
    ".git",
    "build",
    ".gradle",
    ".idea",
    "kl_server",
    "venv",
    "venv_win",
    "node_modules",
    "server",
}

IGNORE_FILES = {"local.properties"}


def encontrar_android_sdk():
    candidatos = [
        os.environ.get("ANDROID_SDK_ROOT"),
        os.environ.get("ANDROID_HOME"),
        "/root/Android/Sdk",
        "/opt/android-sdk",
        os.path.expanduser("~/Android/Sdk"),
    ]
    for caminho in candidatos:
        if not caminho:
            continue
        caminho = os.path.abspath(caminho)
        if os.path.isdir(caminho) and os.path.isdir(os.path.join(caminho, "platforms")):
            return caminho
    return None


def criar_local_properties(projeto_dir):
    sdk_dir = encontrar_android_sdk()
    if not sdk_dir:
        raise RuntimeError(
            "Android SDK nao encontrado. Instale o SDK e defina ANDROID_SDK_ROOT."
        )

    sdk_escapado = sdk_dir.replace("\\", "/")
    if os.name == "nt":
        sdk_escapado = sdk_escapado.replace(":", r"\:")

    conteudo = f"sdk.dir={sdk_escapado}\n"
    caminho = os.path.join(projeto_dir, "local.properties")
    with open(caminho, "w", encoding="utf-8") as arquivo:
        arquivo.write(conteudo)


def verificar_ambiente_build():
    projeto = encontrar_projeto_android()
    sdk = encontrar_android_sdk()
    pillow_ok = True
    try:
        from PIL import Image  # noqa: F401
    except ImportError:
        pillow_ok = False

    return {
        "projeto_android": bool(projeto),
        "projeto_android_caminho": projeto or "",
        "java": java_disponivel(),
        "android_sdk": bool(sdk),
        "android_sdk_caminho": sdk or "",
        "pillow": pillow_ok,
        "pronto": bool(projeto and java_disponivel() and sdk and pillow_ok),
    }


def encontrar_projeto_android():
    candidatos = [
        os.environ.get("ANDROID_PROJECT_PATH"),
        os.path.join(BASE_DIR, "..", "androidstudio"),
        os.path.join(BASE_DIR, "android_template"),
        "/root/androidstudio",
    ]
    for caminho in candidatos:
        if not caminho:
            continue
        caminho = os.path.abspath(caminho)
        gradlew = "gradlew.bat" if os.name == "nt" else "gradlew"
        if os.path.isdir(caminho) and os.path.isfile(os.path.join(caminho, gradlew)):
            return caminho
    return None


def java_disponivel():
    try:
        resultado = subprocess.run(
            ["java", "-version"],
            capture_output=True,
            text=True,
            timeout=15,
        )
        return resultado.returncode == 0
    except Exception:
        return False


def sanitizar_nome_arquivo(nome):
    limpo = re.sub(r"[^\w\-]+", "_", nome.strip(), flags=re.UNICODE)
    return limpo.strip("_") or "app_kl"


def copiar_projeto_android(origem, destino):
    for raiz, pastas, arquivos in os.walk(origem):
        pastas[:] = [p for p in pastas if p not in IGNORE_DIRS]
        rel = os.path.relpath(raiz, origem)
        destino_raiz = destino if rel == "." else os.path.join(destino, rel)
        os.makedirs(destino_raiz, exist_ok=True)

        for arquivo in arquivos:
            if arquivo in IGNORE_FILES:
                continue
            shutil.copy2(
                os.path.join(raiz, arquivo),
                os.path.join(destino_raiz, arquivo),
            )


def atualizar_nome_app(projeto_dir, nome_app):
    strings_path = os.path.join(
        projeto_dir,
        "app",
        "src",
        "main",
        "res",
        "values",
        "strings.xml",
    )
    if not os.path.exists(strings_path):
        raise FileNotFoundError("strings.xml nao encontrado no projeto Android")

    with open(strings_path, "r", encoding="utf-8") as arquivo:
        conteudo = arquivo.read()

    nome_seguro = escape(nome_app.strip())
    conteudo = re.sub(
        r"<string name=\"app_name\">.*?</string>",
        f'<string name="app_name">{nome_seguro}</string>',
        conteudo,
        count=1,
    )

    with open(strings_path, "w", encoding="utf-8") as arquivo:
        arquivo.write(conteudo)


def aplicar_icone_personalizado(projeto_dir, caminho_icone):
    try:
        from PIL import Image
    except ImportError as exc:
        raise RuntimeError(
            "Pillow nao instalado no servidor. Execute: pip install Pillow"
        ) from exc

    res_dir = os.path.join(projeto_dir, "app", "src", "main", "res")
    drawable_dir = os.path.join(res_dir, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)

    imagem = Image.open(caminho_icone).convert("RGBA")
    try:
        resample = Image.Resampling.LANCZOS
    except AttributeError:
        resample = Image.LANCZOS
    imagem = imagem.resize((512, 512), resample)
    destino_icone = os.path.join(drawable_dir, "ic_launcher_custom.png")
    imagem.save(destino_icone, format="PNG")

    tamanhos = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for pasta, tamanho in tamanhos.items():
        pasta_destino = os.path.join(res_dir, pasta)
        os.makedirs(pasta_destino, exist_ok=True)
        redimensionada = imagem.resize((tamanho, tamanho), resample)
        redimensionada.save(os.path.join(pasta_destino, "ic_launcher.png"), format="PNG")
        redimensionada.save(os.path.join(pasta_destino, "ic_launcher_round.png"), format="PNG")

    for nome_xml in ("ic_launcher.xml", "ic_launcher_round.xml"):
        caminho_xml = os.path.join(res_dir, "mipmap-anydpi", nome_xml)
        if os.path.exists(caminho_xml):
            with open(caminho_xml, "w", encoding="utf-8") as arquivo:
                arquivo.write(
                    '<?xml version="1.0" encoding="utf-8"?>\n'
                    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                    '    <background android:drawable="@android:color/white" />\n'
                    '    <foreground android:drawable="@drawable/ic_launcher_custom" />\n'
                    "</adaptive-icon>\n"
                )


def executar_gradle(projeto_dir):
    if os.name == "nt":
        comando = [os.path.join(projeto_dir, "gradlew.bat"), "assembleDebug", "--no-daemon"]
    else:
        gradlew = os.path.join(projeto_dir, "gradlew")
        if not os.access(gradlew, os.X_OK):
            os.chmod(gradlew, 0o755)
        comando = [gradlew, "assembleDebug", "--no-daemon"]

    resultado = subprocess.run(
        comando,
        cwd=projeto_dir,
        capture_output=True,
        text=True,
        timeout=900,
    )
    if resultado.returncode != 0:
        detalhe = (resultado.stderr or resultado.stdout or "").strip()
        if len(detalhe) > 1200:
            detalhe = detalhe[-1200:]
        raise RuntimeError(f"Gradle falhou: {detalhe}")


def gerar_apk(nome_app, caminho_icone=None):
    nome_app = (nome_app or "").strip()
    if not nome_app:
        raise ValueError("Nome do aplicativo obrigatorio")

    projeto_origem = encontrar_projeto_android()
    if not projeto_origem:
        raise RuntimeError(
            "Projeto Android nao encontrado. Defina ANDROID_PROJECT_PATH no servidor."
        )

    if not java_disponivel():
        raise RuntimeError(
            "Java nao encontrado no servidor. Instale JDK 17 para compilar o APK."
        )

    if not encontrar_android_sdk():
        raise RuntimeError(
            "Android SDK nao encontrado. Instale o SDK e defina ANDROID_SDK_ROOT."
        )

    os.makedirs(APK_OUTPUT_DIR, exist_ok=True)
    os.makedirs(ICON_UPLOAD_DIR, exist_ok=True)

    slug = sanitizar_nome_arquivo(nome_app)
    build_id = f"{slug}_{int(time.time())}_{uuid.uuid4().hex[:6]}"
    temp_dir = tempfile.mkdtemp(prefix="kl_apk_")
    projeto_build = os.path.join(temp_dir, "android_build")

    try:
        copiar_projeto_android(projeto_origem, projeto_build)
        criar_local_properties(projeto_build)
        atualizar_nome_app(projeto_build, nome_app)
        if caminho_icone and os.path.exists(caminho_icone):
            aplicar_icone_personalizado(projeto_build, caminho_icone)

        executar_gradle(projeto_build)

        apk_debug = os.path.join(
            projeto_build,
            "app",
            "build",
            "outputs",
            "apk",
            "debug",
            "app-debug.apk",
        )
        if not os.path.exists(apk_debug):
            raise FileNotFoundError("APK compilado nao encontrado apos o build")

        nome_arquivo = f"{build_id}.apk"
        destino_final = os.path.join(APK_OUTPUT_DIR, nome_arquivo)
        shutil.copy2(apk_debug, destino_final)

        return {
            "arquivo": nome_arquivo,
            "nome_app": nome_app,
            "tamanho_bytes": os.path.getsize(destino_final),
            "download_url": f"/download/apk/{nome_arquivo}",
        }
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)
