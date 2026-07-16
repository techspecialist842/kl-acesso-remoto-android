import os
import uuid

from flask import Blueprint, jsonify, render_template, request, send_from_directory
from werkzeug.utils import secure_filename

from services.apk_builder import APK_OUTPUT_DIR, ICON_UPLOAD_DIR, verificar_ambiente_build
from services.apk_jobs import criar_job, obter_job


bp = Blueprint("apk", __name__)

EXTENSOES_ICONE = {".png", ".jpg", ".jpeg", ".webp"}


def obter_url_padrao_painel():
    host = (request.headers.get("Host") or "localhost").split(",")[0].strip()
    proto = request.headers.get("X-Forwarded-Proto", "https")
    return f"{proto}://{host}"


@bp.get("/painel/gerar-apk")
def pagina_gerar_apk():
    return render_template(
        "gerar_apk.html",
        ambiente=verificar_ambiente_build(),
        url_padrao=obter_url_padrao_painel(),
    )


@bp.get("/api/gerar-apk/verificar")
def api_verificar_ambiente():
    return jsonify(verificar_ambiente_build())


@bp.post("/api/gerar-apk")
def api_gerar_apk():
    nome_app = request.form.get("nome_app", "").strip()
    url_servidor = request.form.get("url_servidor", "").strip()
    descricao_servico = request.form.get("descricao_servico", "").strip()

    if not nome_app:
        return jsonify({"erro": "Informe o nome do aplicativo"}), 400
    if not url_servidor:
        url_servidor = obter_url_padrao_painel()

    ambiente = verificar_ambiente_build()
    if not ambiente.get("pronto"):
        return jsonify({"erro": "Servidor incompleto para gerar APK"}), 503

    caminho_icone = None
    arquivo_icone = request.files.get("icone")
    if arquivo_icone and arquivo_icone.filename:
        extensao = os.path.splitext(arquivo_icone.filename)[1].lower()
        if extensao not in EXTENSOES_ICONE:
            return jsonify({"erro": "Icone invalido. Use PNG, JPG ou WEBP"}), 400

        os.makedirs(ICON_UPLOAD_DIR, exist_ok=True)
        nome_seguro = secure_filename(arquivo_icone.filename) or "icone.png"
        caminho_icone = os.path.join(
            ICON_UPLOAD_DIR,
            f"upload_{uuid.uuid4().hex}_{nome_seguro}",
        )
        arquivo_icone.save(caminho_icone)

    try:
        job_id = criar_job(
            nome_app,
            caminho_icone,
            url_servidor=url_servidor,
            descricao_servico=descricao_servico or None,
        )
        return jsonify({
            "status": "processando",
            "job_id": job_id,
            "mensagem": "Compilacao iniciada. Aguarde...",
        })
    except Exception as exc:
        if caminho_icone and os.path.exists(caminho_icone):
            try:
                os.remove(caminho_icone)
            except OSError:
                pass
        return jsonify({"erro": str(exc)}), 500


@bp.get("/api/gerar-apk/status/<job_id>")
def api_status_gerar_apk(job_id):
    dados = obter_job(job_id)
    if not dados:
        return jsonify({"erro": "Job nao encontrado"}), 404

    if dados["status"] == "processando":
        return jsonify({
            "status": "processando",
            "nome_app": dados.get("nome_app"),
        })

    if dados["status"] == "erro":
        return jsonify({
            "status": "erro",
            "erro": dados.get("erro") or "Falha ao gerar APK",
        })

    resultado = dados.get("resultado") or {}
    return jsonify({
        "status": "ok",
        **resultado,
    })


@bp.get("/download/apk/<path:nome_arquivo>")
def download_apk(nome_arquivo):
    if not nome_arquivo or ".." in nome_arquivo or "/" in nome_arquivo or "\\" in nome_arquivo:
        return jsonify({"erro": "arquivo invalido"}), 400

    caminho = os.path.join(APK_OUTPUT_DIR, nome_arquivo)
    if not os.path.exists(caminho):
        return jsonify({"erro": "APK nao encontrado"}), 404

    return send_from_directory(
        APK_OUTPUT_DIR,
        nome_arquivo,
        as_attachment=True,
        download_name=nome_arquivo,
    )
