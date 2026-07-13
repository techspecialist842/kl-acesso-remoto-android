import os

from flask import Blueprint, jsonify, render_template, request, send_from_directory
from werkzeug.utils import secure_filename

from services.apk_builder import APK_OUTPUT_DIR, ICON_UPLOAD_DIR, gerar_apk


bp = Blueprint("apk", __name__)

EXTENSOES_ICONE = {".png", ".jpg", ".jpeg", ".webp"}


@bp.get("/painel/gerar-apk")
def pagina_gerar_apk():
    return render_template("gerar_apk.html")


@bp.post("/api/gerar-apk")
def api_gerar_apk():
    nome_app = request.form.get("nome_app", "").strip()
    if not nome_app:
        return jsonify({"erro": "Informe o nome do aplicativo"}), 400

    caminho_icone = None
    arquivo_icone = request.files.get("icone")
    if arquivo_icone and arquivo_icone.filename:
        extensao = os.path.splitext(arquivo_icone.filename)[1].lower()
        if extensao not in EXTENSOES_ICONE:
            return jsonify({"erro": "Icone invalido. Use PNG, JPG ou WEBP"}), 400

        os.makedirs(ICON_UPLOAD_DIR, exist_ok=True)
        nome_seguro = secure_filename(arquivo_icone.filename)
        caminho_icone = os.path.join(ICON_UPLOAD_DIR, f"upload_{nome_seguro}")
        arquivo_icone.save(caminho_icone)

    try:
        resultado = gerar_apk(nome_app, caminho_icone)
        return jsonify({"status": "ok", **resultado})
    except Exception as exc:
        return jsonify({"erro": str(exc)}), 500
    finally:
        if caminho_icone and os.path.exists(caminho_icone):
            try:
                os.remove(caminho_icone)
            except OSError:
                pass


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
