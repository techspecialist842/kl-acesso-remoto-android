from flask import Blueprint, render_template, jsonify, request, abort
from routes.dispositivos import (
    obter_dispositivos_ativos,
    dispositivo_bloqueado,
    renomear_dispositivo,
    remover_dispositivo,
    usuario_pode_acessar_dispositivo,
)
from routes.esqueleto import carregar_esqueletos
from services.auth import login_obrigatorio, sessao_atual
from services.monitoramento import listar_dispositivos_monitoramento

bp = Blueprint("painel", __name__)


@bp.get("/painel")
@login_obrigatorio
def index():
    return render_template("painel.html")


@bp.get("/api/painel/dispositivos")
@login_obrigatorio
def api_dispositivos_monitor():
    return jsonify(listar_dispositivos_monitoramento(sessao_atual()))


@bp.patch("/api/painel/dispositivos/<id>")
@login_obrigatorio
def api_renomear_dispositivo(id):
    dados = request.get_json(silent=True) or {}
    try:
        dispositivo = renomear_dispositivo(id, dados.get("nome", ""), sessao_atual())
        return jsonify({"status": "ok", "dispositivo": dispositivo})
    except ValueError as exc:
        mensagem = str(exc)
        codigo = 403 if mensagem == "acesso negado" else 404
        return jsonify({"erro": mensagem}), codigo


@bp.delete("/api/painel/dispositivos/<id>")
@login_obrigatorio
def api_remover_dispositivo(id):
    try:
        remover_dispositivo(id, sessao_atual())
        return jsonify({"status": "ok"})
    except ValueError as exc:
        mensagem = str(exc)
        codigo = 403 if mensagem == "acesso negado" else 404
        return jsonify({"erro": mensagem}), codigo


@bp.get("/painel/dispositivo/<id>")
@login_obrigatorio
def dispositivo(id):
    if dispositivo_bloqueado(id):
        abort(404)

    usuario = sessao_atual()
    dados = obter_dispositivos_ativos().get(id)
    if dados and not usuario_pode_acessar_dispositivo(usuario, dados):
        abort(403)

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

    esqueleto = carregar_esqueletos().get(id, {})

    return render_template(
        "dispositivo.html",
        dispositivo=dados,
        esqueleto=esqueleto
    )


@bp.get("/api/esqueleto/<id>")
@login_obrigatorio
def api_esqueleto(id):
    if dispositivo_bloqueado(id):
        return jsonify({})

    usuario = sessao_atual()
    dados = obter_dispositivos_ativos().get(id)
    if dados and not usuario_pode_acessar_dispositivo(usuario, dados):
        return jsonify({}), 403

    return jsonify(carregar_esqueletos().get(id, {}))
