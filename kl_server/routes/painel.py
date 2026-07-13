from flask import Blueprint, render_template, jsonify
from routes.dispositivos import dispositivos
from routes.esqueleto import esqueletos

bp = Blueprint("painel", __name__)


@bp.get("/painel")
def index():

    lista = list(dispositivos.values())

    return render_template(
        "painel.html",
        dispositivos=lista
    )


@bp.get("/painel/dispositivo/<id>")
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
def api_esqueleto(id):

    return jsonify(
        esqueletos.get(id, {})
    )
