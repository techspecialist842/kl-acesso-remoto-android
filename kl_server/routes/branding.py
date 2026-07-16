from flask import Blueprint, jsonify, render_template, request

from services.branding import obter_branding, salvar_branding


bp = Blueprint("branding", __name__)


@bp.get("/painel/personalizar")
def pagina_personalizar():
    return render_template("personalizar.html", branding=obter_branding())


@bp.get("/api/branding")
def api_obter_branding():
    return jsonify(obter_branding())


@bp.post("/api/branding")
def api_salvar_branding():
    dados = request.get_json(silent=True) or request.form.to_dict()
    if not dados:
        return jsonify({"erro": "Nenhum dado recebido"}), 400
    branding = salvar_branding(dados)
    return jsonify({"status": "ok", **branding})
