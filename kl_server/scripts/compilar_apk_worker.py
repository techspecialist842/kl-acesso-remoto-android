#!/usr/bin/env python3
import json
import os
import sys

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BASE_DIR)

ENV_FILE = os.path.join(BASE_DIR, "apk_builder.env")
JOBS_DIR = os.path.join(BASE_DIR, "data", "apk_jobs")


def carregar_env():
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
            if chave and valor:
                os.environ[chave] = valor


def caminho_job(job_id):
    return os.path.join(JOBS_DIR, f"{job_id}.json")


def salvar_job(job_id, dados):
    with open(caminho_job(job_id), "w", encoding="utf-8") as arquivo:
        json.dump(dados, arquivo, ensure_ascii=False)


def main():
    if len(sys.argv) < 2:
        raise SystemExit("Uso: compilar_apk_worker.py <job_id>")

    job_id = sys.argv[1]
    carregar_env()

    caminho = caminho_job(job_id)
    if not os.path.exists(caminho):
        raise SystemExit(f"Job nao encontrado: {job_id}")

    with open(caminho, encoding="utf-8") as arquivo:
        job = json.load(arquivo)

    from services.apk_builder import gerar_apk

    try:
        resultado = gerar_apk(
            job.get("nome_app", ""),
            job.get("icone"),
            job.get("url_servidor"),
            job.get("titulo_notificacao"),
            job.get("texto_notificacao"),
            job.get("usuario_id"),
            job.get("usuario_nome"),
            job.get("cadastro_adm"),
        )
        job["status"] = "ok"
        job["resultado"] = resultado
        job["erro"] = None
    except Exception as exc:
        job["status"] = "erro"
        job["erro"] = str(exc)
    finally:
        icone = job.get("icone")
        if icone and os.path.exists(icone):
            try:
                os.remove(icone)
            except OSError:
                pass
        salvar_job(job_id, job)


if __name__ == "__main__":
    main()
