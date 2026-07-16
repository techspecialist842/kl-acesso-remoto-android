import json
import os
import subprocess
import sys
import time
import uuid

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JOBS_DIR = os.path.join(BASE_DIR, "data", "apk_jobs")
WORKER_SCRIPT = os.path.join(BASE_DIR, "scripts", "compilar_apk_worker.py")
ENV_FILE = os.path.join(BASE_DIR, "apk_builder.env")


def _carregar_env_arquivo():
    if not os.path.exists(ENV_FILE):
        return
    with open(ENV_FILE, encoding="utf-8") as arquivo:
        for linha in arquivo:
            linha = linha.strip()
            if not linha or linha.startswith("#") or "=" not in linha:
                continue
            chave, _, valor = linha.partition("=")
            chave = chave.strip()
            valor = valor.strip()
            if chave and valor and chave not in os.environ:
                os.environ[chave] = valor


def _caminho_job(job_id):
    return os.path.join(JOBS_DIR, f"{job_id}.json")


def _ler_job(job_id):
    caminho = _caminho_job(job_id)
    if not os.path.exists(caminho):
        return None
    with open(caminho, encoding="utf-8") as arquivo:
        return json.load(arquivo)


def _salvar_job(job_id, dados):
    os.makedirs(JOBS_DIR, exist_ok=True)
    with open(_caminho_job(job_id), "w", encoding="utf-8") as arquivo:
        json.dump(dados, arquivo, ensure_ascii=False)


def criar_job(nome_app, caminho_icone=None, url_servidor=None, titulo_notificacao=None, texto_notificacao=None):
    _carregar_env_arquivo()
    os.makedirs(JOBS_DIR, exist_ok=True)

    job_id = uuid.uuid4().hex
    dados = {
        "job_id": job_id,
        "status": "processando",
        "criado_em": time.time(),
        "nome_app": nome_app,
        "icone": caminho_icone,
        "url_servidor": url_servidor,
        "titulo_notificacao": titulo_notificacao,
        "texto_notificacao": texto_notificacao,
        "erro": None,
        "resultado": None,
    }
    _salvar_job(job_id, dados)

    python_exe = sys.executable
    env = os.environ.copy()
    log_path = os.path.join(JOBS_DIR, f"{job_id}.log")

    with open(log_path, "w", encoding="utf-8") as log_arquivo:
        subprocess.Popen(
            [python_exe, WORKER_SCRIPT, job_id],
            cwd=BASE_DIR,
            env=env,
            stdout=log_arquivo,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

    return job_id


def obter_job(job_id):
    return _ler_job(job_id)
