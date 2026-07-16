import os
import re
import shutil
import subprocess
import tempfile
import time
import uuid
from urllib.parse import urlparse
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
    raiz_repo = os.path.abspath(os.path.join(BASE_DIR, ".."))
    candidatos = [
        os.environ.get("ANDROID_PROJECT_PATH"),
        raiz_repo,
        os.path.join(BASE_DIR, "..", "androidstudio"),
        os.path.join(BASE_DIR, "android_template"),
        "/root/kl-acesso-remoto-android",
        "/root/androidstudio",
        "/var/www/kl-acesso-remoto-android",
    ]
    vistos = set()
    for caminho in candidatos:
        if not caminho:
            continue
        caminho = os.path.abspath(caminho)
        if caminho in vistos:
            continue
        vistos.add(caminho)
        gradlew = "gradlew.bat" if os.name == "nt" else "gradlew"
        if os.path.isdir(caminho) and os.path.isfile(os.path.join(caminho, gradlew)):
            return caminho
    return None


def java_disponivel():
    comandos = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        comandos.append(os.path.join(java_home, "bin", "java"))
    comandos.append("java")

    for comando in comandos:
        try:
            resultado = subprocess.run(
                [comando, "-version"],
                capture_output=True,
                text=True,
                timeout=15,
            )
            if resultado.returncode == 0:
                return True
        except Exception:
            continue
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
    nome_app = nome_app.strip()
    if not nome_app:
        raise ValueError("Nome do aplicativo obrigatorio")

    nome_xml = escape(nome_app)
    nome_kotlin = escapar_kotlin(nome_app)

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

    conteudo = re.sub(
        r'<string name="app_name">.*?</string>',
        f'<string name="app_name">{nome_xml}</string>',
        conteudo,
        count=1,
    )
    with open(strings_path, "w", encoding="utf-8") as arquivo:
        arquivo.write(conteudo)

    manifest_path = os.path.join(
        projeto_dir, "app", "src", "main", "AndroidManifest.xml"
    )
    if os.path.exists(manifest_path):
        with open(manifest_path, encoding="utf-8") as arquivo:
            conteudo = arquivo.read()
        conteudo = re.sub(
            r'(<application[^>]*android:label=")[^"]*(")',
            rf'\1{nome_xml}\2',
            conteudo,
            count=1,
        )
        with open(manifest_path, "w", encoding="utf-8") as arquivo:
            arquivo.write(conteudo)

    layout_path = os.path.join(
        projeto_dir, "app", "src", "main", "res", "layout", "activity_main.xml"
    )
    if os.path.exists(layout_path):
        with open(layout_path, encoding="utf-8") as arquivo:
            conteudo = arquivo.read()
        conteudo = re.sub(
            r'android:text="[^"]*"',
            f'android:text="{nome_xml}"',
            conteudo,
            count=1,
        )
        with open(layout_path, "w", encoding="utf-8") as arquivo:
            arquivo.write(conteudo)

    main_activity = os.path.join(
        projeto_dir,
        "app",
        "src",
        "main",
        "java",
        "com",
        "meuacesso",
        "remoto",
        "MainActivity.kt",
    )
    if os.path.exists(main_activity):
        with open(main_activity, encoding="utf-8") as arquivo:
            conteudo = arquivo.read()
        for antigo in ("KL Acesso Remoto", "Acesso Remoto"):
            conteudo = conteudo.replace(antigo, nome_app)
        with open(main_activity, "w", encoding="utf-8") as arquivo:
            arquivo.write(conteudo)

    service_path = os.path.join(
        projeto_dir,
        "app",
        "src",
        "main",
        "java",
        "com",
        "meuacesso",
        "remoto",
        "ControleGestosService.kt",
    )
    if os.path.exists(service_path):
        with open(service_path, encoding="utf-8") as arquivo:
            conteudo = arquivo.read()
        conteudo = re.sub(
            r'("Controle Remoto")',
            f'"{nome_kotlin}"',
            conteudo,
            count=1,
        )
        with open(service_path, "w", encoding="utf-8") as arquivo:
            arquivo.write(conteudo)


def limpar_recursos_icone_antigos(res_dir):
    """Remove PNG/WebP antigos para evitar Duplicate resources no Gradle."""
    nomes = {"ic_launcher", "ic_launcher_round"}
    for raiz, _, arquivos in os.walk(res_dir):
        pasta = os.path.basename(raiz)
        if not pasta.startswith("mipmap"):
            continue
        for arquivo in arquivos:
            nome, ext = os.path.splitext(arquivo)
            if nome in nomes and ext.lower() in {".png", ".webp", ".jpg", ".jpeg"}:
                try:
                    os.remove(os.path.join(raiz, arquivo))
                except OSError:
                    pass


def aplicar_icone_personalizado(projeto_dir, caminho_icone):
    try:
        from PIL import Image
    except ImportError as exc:
        raise RuntimeError(
            "Pillow nao instalado no servidor. Execute: pip install Pillow"
        ) from exc

    res_dir = os.path.join(projeto_dir, "app", "src", "main", "res")
    limpar_recursos_icone_antigos(res_dir)
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
        os.makedirs(os.path.dirname(caminho_xml), exist_ok=True)
        with open(caminho_xml, "w", encoding="utf-8") as arquivo:
                arquivo.write(
                    '<?xml version="1.0" encoding="utf-8"?>\n'
                    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                    '    <background android:drawable="@android:color/white" />\n'
                    '    <foreground android:drawable="@drawable/ic_launcher_custom" />\n'
                    "</adaptive-icon>\n"
                )


def detectar_heap_gradle():
    """Ajusta RAM do Gradle conforme memoria do servidor."""
    try:
        with open("/proc/meminfo", encoding="utf-8") as arquivo:
            for linha in arquivo:
                if linha.startswith("MemTotal:"):
                    kb = int(linha.split()[1])
                    if kb >= 7_000_000:
                        return "2048m", "512m"
                    if kb >= 3_000_000:
                        return "1536m", "384m"
                    return "768m", "256m"
    except Exception:
        pass
    return "1024m", "320m"


def encontrar_java_home():
    candidatos = [
        os.environ.get("JAVA_HOME"),
        "/usr/lib/jvm/java-17-openjdk-amd64",
        "/usr/lib/jvm/java-17-openjdk",
        "/usr/lib/jvm/java-17-openjdk-arm64",
    ]
    for caminho in candidatos:
        if caminho and os.path.isdir(caminho) and os.path.isfile(
            os.path.join(caminho, "bin", "java")
        ):
            return caminho

    if os.name != "nt":
        try:
            resultado = subprocess.run(
                ["bash", "-lc", "dirname $(dirname $(readlink -f $(which java)))"],
                capture_output=True,
                text=True,
                timeout=10,
            )
            caminho = resultado.stdout.strip()
            if resultado.returncode == 0 and caminho and os.path.isdir(caminho):
                return caminho
        except Exception:
            pass
    return None


def otimizar_gradle_para_vps(projeto_dir):
    """Reduz uso de RAM no VPS para evitar OOM killer durante assembleDebug."""
    daemon_jvm = os.path.join(projeto_dir, "gradle", "gradle-daemon-jvm.properties")
    if os.path.exists(daemon_jvm):
        os.remove(daemon_jvm)

    caminho = os.path.join(projeto_dir, "gradle.properties")
    linhas = []
    if os.path.exists(caminho):
        with open(caminho, encoding="utf-8") as arquivo:
            linhas = arquivo.read().splitlines()

    heap, metaspace = detectar_heap_gradle()
    substituicoes = {
        "org.gradle.jvmargs": f"-Xmx{heap} -XX:MaxMetaspaceSize={metaspace} -Dfile.encoding=UTF-8",
        "org.gradle.parallel": "true",
        "org.gradle.daemon": "false",
        "org.gradle.workers.max": "2",
        "kotlin.daemon.jvmargs": f"-Xmx{metaspace}",
        "org.gradle.java.installations.auto-download": "false",
        "org.gradle.java.installations.auto-detect": "true",
    }

    chaves_vistas = set()
    novas_linhas = []
    for linha in linhas:
        chave = linha.split("=", 1)[0].strip() if "=" in linha else ""
        if chave in substituicoes:
            novas_linhas.append(f"{chave}={substituicoes[chave]}")
            chaves_vistas.add(chave)
        else:
            novas_linhas.append(linha)

    for chave, valor in substituicoes.items():
        if chave not in chaves_vistas:
            novas_linhas.append(f"{chave}={valor}")

    with open(caminho, "w", encoding="utf-8") as arquivo:
        arquivo.write("\n".join(novas_linhas) + "\n")


def preparar_env_gradle(env=None):
    env = dict(env or os.environ)
    java_home = env.get("JAVA_HOME") or encontrar_java_home()
    bases = ["/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin"]
    if java_home:
        bases.insert(0, os.path.join(java_home, "bin"))
    sdk = env.get("ANDROID_SDK_ROOT") or env.get("ANDROID_HOME")
    if sdk:
        bases.insert(0, os.path.join(sdk, "cmdline-tools", "latest", "bin"))
        bases.insert(0, os.path.join(sdk, "platform-tools"))
    atual = env.get("PATH", "")
    env["PATH"] = os.pathsep.join(bases + ([atual] if atual else []))
    env["GRADLE_OPTS"] = "-Dorg.gradle.daemon=false -Xmx512m -XX:MaxMetaspaceSize=192m"
    env["JAVA_TOOL_OPTIONS"] = "-Xmx512m"
    env["GRADLE_USER_HOME"] = env.get("GRADLE_USER_HOME") or "/root/.gradle"
    if java_home:
        env["JAVA_HOME"] = java_home
        env["ORG_GRADLE_JAVA_HOME"] = java_home
    else:
        raise RuntimeError(
            "JAVA_HOME nao encontrado. Instale JDK 17: apt install openjdk-17-jdk"
        )
    return env


def executar_gradle(projeto_dir):
    env = preparar_env_gradle()

    if os.name == "nt":
        comando = [os.path.join(projeto_dir, "gradlew.bat"), "assembleDebug", "--no-daemon"]
    else:
        gradlew = os.path.join(projeto_dir, "gradlew")
        if not os.access(gradlew, os.X_OK):
            os.chmod(gradlew, 0o755)
        subprocess.run(
            [gradlew, "--stop"],
            cwd=projeto_dir,
            capture_output=True,
            text=True,
            timeout=60,
            env=env,
        )
        comando = [
            gradlew,
            "assembleDebug",
            "--no-daemon",
            "--max-workers=1",
            "-Dorg.gradle.java.home=" + env["JAVA_HOME"],
            "-Dorg.gradle.daemon=false",
        ]

    resultado = subprocess.run(
        comando,
        cwd=projeto_dir,
        capture_output=True,
        text=True,
        timeout=900,
        env=env,
    )
    if resultado.returncode != 0:
        detalhe = (resultado.stderr or resultado.stdout or "").strip()
        if len(detalhe) > 1200:
            detalhe = detalhe[-1200:]
        raise RuntimeError(f"Gradle falhou: {detalhe}")


def escapar_kotlin(texto):
    return (texto or "").replace("\\", "\\\\").replace('"', '\\"')


def normalizar_url_servidor(url):
    valor = (url or "").strip()
    if not valor:
        raise ValueError("URL do servidor obrigatoria")
    if not valor.startswith(("http://", "https://")):
        valor = "https://" + valor
    parsed = urlparse(valor)
    if not parsed.netloc or not parsed.hostname:
        raise ValueError("URL do servidor invalida. Ex: https://meudominio.com ou http://123.45.67.89")
    base = f"{parsed.scheme}://{parsed.netloc}".rstrip("/")
    porta = parsed.port or (443 if parsed.scheme == "https" else 80)
    return base, parsed.hostname, porta


def atualizar_config_apk_no_projeto(
    projeto_dir,
    url_servidor,
    titulo_notificacao,
    texto_notificacao,
):
    url_base, host, porta = normalizar_url_servidor(url_servidor)
    titulo = escapar_kotlin(titulo_notificacao)
    texto = escapar_kotlin(texto_notificacao)

    service_path = os.path.join(
        projeto_dir,
        "app",
        "src",
        "main",
        "java",
        "com",
        "meuacesso",
        "remoto",
        "ControleGestosService.kt",
    )
    if not os.path.exists(service_path):
        raise FileNotFoundError("ControleGestosService.kt nao encontrado")

    with open(service_path, encoding="utf-8") as arquivo:
        conteudo = arquivo.read()

    conteudo = re.sub(
        r'const val URL_VPS = ".*?"',
        f'const val URL_VPS = "{escapar_kotlin(url_base)}"',
        conteudo,
        count=1,
    )
    conteudo = re.sub(
        r'\.setContentTitle\(".*?"\)',
        f'.setContentTitle("{titulo}")',
        conteudo,
        count=1,
    )
    conteudo = re.sub(
        r'\.setContentText\(".*?"\)',
        f'.setContentText("{texto}")',
        conteudo,
        count=1,
    )

    with open(service_path, "w", encoding="utf-8") as arquivo:
        arquivo.write(conteudo)

    constantes_path = os.path.join(
        projeto_dir,
        "app",
        "src",
        "main",
        "java",
        "com",
        "meuacesso",
        "remoto",
        "Constantes.kt",
    )
    if os.path.exists(constantes_path):
        with open(constantes_path, encoding="utf-8") as arquivo:
            conteudo = arquivo.read()
        conteudo = re.sub(
            r'const val SERVIDOR_HOST = ".*?"',
            f'const val SERVIDOR_HOST = "{escapar_kotlin(host)}"',
            conteudo,
            count=1,
        )
        conteudo = re.sub(
            r"const val SERVIDOR_PORTA = \d+",
            f"const val SERVIDOR_PORTA = {porta}",
            conteudo,
            count=1,
        )
        with open(constantes_path, "w", encoding="utf-8") as arquivo:
            arquivo.write(conteudo)

    captura_path = os.path.join(
        projeto_dir,
        "app",
        "src",
        "main",
        "java",
        "com",
        "meuacesso",
        "remoto",
        "CapturaTelaService.kt",
    )
    if os.path.exists(captura_path):
        with open(captura_path, encoding="utf-8") as arquivo:
            conteudo = arquivo.read()
        conteudo = re.sub(
            r'https?://[^"/]+',
            escapar_kotlin(url_base),
            conteudo,
        )
        with open(captura_path, "w", encoding="utf-8") as arquivo:
            arquivo.write(conteudo)


def gerar_apk(
    nome_app,
    caminho_icone=None,
    url_servidor=None,
    titulo_notificacao=None,
    texto_notificacao=None,
):
    nome_app = (nome_app or "").strip()
    if not nome_app:
        raise ValueError("Nome do aplicativo obrigatorio")

    if not url_servidor:
        raise ValueError("URL do servidor obrigatoria")

    titulo_notificacao = (titulo_notificacao or f"{nome_app} Ativo").strip()
    texto_notificacao = (
        texto_notificacao or "Monitorando tela e aguardando comandos"
    ).strip()

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
        otimizar_gradle_para_vps(projeto_build)
        atualizar_config_apk_no_projeto(
            projeto_build,
            url_servidor,
            titulo_notificacao,
            texto_notificacao,
        )
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
