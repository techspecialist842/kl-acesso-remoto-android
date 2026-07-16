import os
import threading
import time
import uuid

from services.apk_builder import gerar_apk

_jobs = {}
_lock = threading.Lock()
MAX_JOBS = 20


def _limpar_jobs_antigos():
    agora = time.time()
    with _lock:
        expirados = [
            job_id
            for job_id, dados in _jobs.items()
            if agora - dados.get("criado_em", agora) > 3600
        ]
        for job_id in expirados:
            _jobs.pop(job_id, None)


def criar_job(nome_app, caminho_icone=None):
    _limpar_jobs_antigos()

    job_id = uuid.uuid4().hex
    with _lock:
        if len(_jobs) >= MAX_JOBS:
            raise RuntimeError("Muitas compilacoes em andamento. Aguarde e tente novamente.")
        _jobs[job_id] = {
            "status": "processando",
            "criado_em": time.time(),
            "nome_app": nome_app,
            "erro": None,
            "resultado": None,
        }

    def worker():
        try:
            resultado = gerar_apk(nome_app, caminho_icone)
            with _lock:
                _jobs[job_id]["status"] = "ok"
                _jobs[job_id]["resultado"] = resultado
        except Exception as exc:
            with _lock:
                _jobs[job_id]["status"] = "erro"
                _jobs[job_id]["erro"] = str(exc)
        finally:
            if caminho_icone and os.path.exists(caminho_icone):
                try:
                    os.remove(caminho_icone)
                except OSError:
                    pass

    threading.Thread(target=worker, daemon=True).start()
    return job_id


def obter_job(job_id):
    with _lock:
        dados = _jobs.get(job_id)
        if not dados:
            return None
        return dict(dados)
