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
    personalizar_marca_apk(
        projeto_dir,
        nome_app,
        os.environ.get("APK_URL_PADRAO", "https://servidorpremium-kl.lat"),
    )


def normalizar_url_servidor(url):
    url = (url or "").strip().rstrip("/")
    if not url:
        raise ValueError("URL do servidor obrigatoria")
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    return url


def extrair_host_url(url):
    from urllib.parse import urlparse

    return urlparse(url).netloc or url.replace("https://", "").replace("http://", "").split("/")[0]


def atualizar_string_xml(projeto_dir, chave, valor):
    strings_path = os.path.join(
        projeto_dir, "app", "src", "main", "res", "values", "strings.xml"
    )
    if not os.path.exists(strings_path):
        raise FileNotFoundError("strings.xml nao encontrado no projeto Android")

    with open(strings_path, "r", encoding="utf-8") as arquivo:
        conteudo = arquivo.read()

    valor_seguro = escape(str(valor).strip())
    padrao = rf'<string name="{re.escape(chave)}">.*?</string>'
    substituto = f'<string name="{chave}">{valor_seguro}</string>'
    if re.search(padrao, conteudo):
        conteudo = re.sub(padrao, substituto, conteudo, count=1)
    else:
        conteudo = conteudo.replace(
            "</resources>", f"    {substituto}\n</resources>"
        )

    with open(strings_path, "w", encoding="utf-8") as arquivo:
        arquivo.write(conteudo)


def substituir_em_arquivos(projeto_dir, rel_paths, substituicoes):
    for rel in rel_paths:
        caminho = os.path.join(projeto_dir, rel.replace("/", os.sep))
        if not os.path.exists(caminho):
            continue
        with open(caminho, "r", encoding="utf-8") as arquivo:
            conteudo = arquivo.read()
        for antigo, novo in substituicoes.items():
            if antigo:
                conteudo = conteudo.replace(antigo, novo)
        with open(caminho, "w", encoding="utf-8") as arquivo:
            arquivo.write(conteudo)


def aplicar_regex_em_arquivos(projeto_dir, rel_paths, padroes):
    for rel in rel_paths:
        caminho = os.path.join(projeto_dir, rel.replace("/", os.sep))
        if not os.path.exists(caminho):
            continue
        with open(caminho, "r", encoding="utf-8") as arquivo:
            conteudo = arquivo.read()
        for padrao, substituto in padroes:
            conteudo = re.sub(padrao, substituto, conteudo)
        with open(caminho, "w", encoding="utf-8") as arquivo:
            arquivo.write(conteudo)


def personalizar_marca_apk(projeto_dir, nome_app, url_servidor, descricao_servico=None):
    nome_app = (nome_app or "").strip()
    if not nome_app:
        raise ValueError("Nome do aplicativo obrigatorio")

    url_servidor = normalizar_url_servidor(url_servidor)
    host = extrair_host_url(url_servidor)
    descricao = (descricao_servico or f"Servico remoto {nome_app}").strip()
    nome_js = nome_app.replace("\\", "\\\\").replace('"', '\\"')

    atualizar_string_xml(projeto_dir, "app_name", nome_app)
    atualizar_string_xml(projeto_dir, "servico_descricao", descricao)

    kotlin_arquivos = [
        "app/src/main/java/com/meuacesso/remoto/ControleGestosService.kt",
        "app/src/main/java/com/meuacesso/remoto/Constantes.kt",
        "app/src/main/java/com/meuacesso/remoto/CapturaTelaService.kt",
        "app/src/main/java/com/meuacesso/remoto/MainActivity.kt",
    ]

    aplicar_regex_em_arquivos(
        projeto_dir,
        kotlin_arquivos,
        [
            (r'const val URL_VPS = "https?://[^"]+"', f'const val URL_VPS = "{url_servidor}"'),
            (r'const val SERVIDOR_HOST = "[^"]+"', f'const val SERVIDOR_HOST = "{host}"'),
            (
                r'private const val URL_ENVIO_TELA = "https?://[^"]+"',
                f'private const val URL_ENVIO_TELA = "{url_servidor}/receber_tela.php"',
            ),
            (
                r'private const val URL_BUSCAR_COMANDO = "https?://[^"]+"',
                f'private const val URL_BUSCAR_COMANDO = "{url_servidor}/obter_comando.php"',
            ),
            (
                r'\.setContentTitle\("[^"]+"\)',
                f'.setContentTitle("{nome_js} Ativo")',
            ),
        ],
    )

    substituir_em_arquivos(
        projeto_dir,
        ["app/src/main/java/com/meuacesso/remoto/MainActivity.kt"],
        {
            "KL Acesso Remoto": nome_app,
            "Procure por: KL Acesso Remoto": f"Procure por: {nome_app}",
        },
    )


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


def gerar_apk(nome_app, caminho_icone=None, url_servidor=None, descricao_servico=None):
    nome_app = (nome_app or "").strip()
    if not nome_app:
        raise ValueError("Nome do aplicativo obrigatorio")
    if not url_servidor:
        raise ValueError("URL do servidor obrigatoria")

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
        personalizar_marca_apk(
            projeto_build,
            nome_app,
            url_servidor,
            descricao_servico=descricao_servico,
        )
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
