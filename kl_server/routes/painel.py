from flask import Blueprint, render_template, jsonify, request
from routes.dispositivos import dispositivos, renomear_dispositivo, remover_dispositivo
from routes.esqueleto import esqueletos
from services.auth import login_obrigatorio
from services.monitoramento import listar_dispositivos_monitoramento

bp = Blueprint("painel", __name__)


@bp.get("/painel")
@login_obrigatorio
def index():
    return render_template("painel.html")


@bp.get("/api/painel/dispositivos")
@login_obrigatorio
def api_dispositivos_monitor():
    return jsonify(listar_dispositivos_monitoramento())


@bp.patch("/api/painel/dispositivos/<id>")
@login_obrigatorio
def api_renomear_dispositivo(id):
    dados = request.get_json(silent=True) or {}
    try:
        dispositivo = renomear_dispositivo(id, dados.get("nome", ""))
        return jsonify({"status": "ok", "dispositivo": dispositivo})
    except ValueError as exc:
        return jsonify({"erro": str(exc)}), 404


@bp.delete("/api/painel/dispositivos/<id>")
@login_obrigatorio
def api_remover_dispositivo(id):
    try:
        remover_dispositivo(id)
        return jsonify({"status": "ok"})
    except ValueError as exc:
        return jsonify({"erro": str(exc)}), 404


@bp.get("/painel/dispositivo/<id>")
@login_obrigatorio
def dispositivo(id):

    dados = dispositivos.get(id)
    if not dados:
        dados = {
            "id": id,
            "modelo": "Desconhecido",
            "fabricante": "",
            "android": "",
            "largura": 1080,
            "altura": 2400,
            "status": "offline",
        }

    esqueleto = esqueletos.get(id, {})

    return render_template(
        "dispositivo.html",
        dispositivo=dados,
        esqueleto=esqueleto
    )


@bp.get("/api/esqueleto/<id>")
@login_obrigatorio
def api_esqueleto(id):

    return jsonify(
        esqueletos.get(id, {})
    )
