import json
import os

from flask import Blueprint, request, jsonify, send_from_directory


bp = Blueprint("overlay", __name__)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CAMINHO_OVERLAYS = os.path.join(BASE_DIR, "data", "overlays.json")
CAMINHO_LOGOS = os.path.join(BASE_DIR, "data", "logos")


def carregar_overlays():
    if not os.path.exists(CAMINHO_OVERLAYS):
        return {}
    try:
        with open(CAMINHO_OVERLAYS, "r", encoding="utf-8") as arquivo:
            dados = json.load(arquivo)
            if isinstance(dados, dict):
                return dados
    except Exception:
        pass
    return {}


def salvar_overlays():
    os.makedirs(os.path.dirname(CAMINHO_OVERLAYS), exist_ok=True)
    with open(CAMINHO_OVERLAYS, "w", encoding="utf-8") as arquivo:
        json.dump(overlays, arquivo, ensure_ascii=False, indent=4)


overlays = carregar_overlays()


@bp.get("/obter_overlay")
def obter():
    dispositivo_id = request.args.get("id")
    if not dispositivo_id:
        return jsonify({"ativo": False})

    dados = overlays.get(dispositivo_id, {"ativo": False})
    return jsonify(dados)


@bp.get("/logo_overlay/<path:nome>")
def logo_overlay(nome):
    if not nome or ".." in nome:
        return jsonify({"erro": "arquivo invalido"}), 400
    os.makedirs(CAMINHO_LOGOS, exist_ok=True)
    return send_from_directory(CAMINHO_LOGOS, nome)


@bp.post("/salvar_overlay")
def salvar():
    dados = request.get_json(silent=True)
    if not dados:
        return jsonify({"erro": "dados inválidos"}), 400

    dispositivo_id = dados.get("dispositivo")
    if not dispositivo_id:
        return jsonify({"erro": "dispositivo obrigatório"}), 400

    overlays[dispositivo_id] = {
        "ativo": True,
        "mensagem": dados.get("mensagem", "Aguarde..."),
        "texto_inferior": dados.get("texto_inferior", ""),
        "logo": dados.get("logo", "")
    }
    salvar_overlays()
    return jsonify({"status": "ok"})


@bp.post("/atualizar_overlay_texto")
def atualizar_texto():
    dados = request.get_json(silent=True)
    if not dados:
        return jsonify({"erro": "dados inválidos"}), 400

    dispositivo_id = dados.get("dispositivo")
    if not dispositivo_id:
        return jsonify({"erro": "dispositivo obrigatório"}), 400

    atual = overlays.get(dispositivo_id, {"ativo": False})
    atual["mensagem"] = dados.get("mensagem", atual.get("mensagem", ""))
    atual["texto_inferior"] = dados.get("texto_inferior", atual.get("texto_inferior", ""))
    if dados.get("logo"):
        atual["logo"] = dados.get("logo")
    if dados.get("manter_ativo") or atual.get("ativo"):
        atual["ativo"] = True
    overlays[dispositivo_id] = atual
    salvar_overlays()
    return jsonify({"status": "ok"})


@bp.post("/desativar_overlay")
def desativar():
    dados = request.get_json(silent=True)
    if not dados:
        return jsonify({"erro": "dados inválidos"}), 400

    dispositivo_id = dados.get("dispositivo")
    if not dispositivo_id:
        return jsonify({"erro": "dispositivo obrigatório"}), 400

    overlays[dispositivo_id] = {"ativo": False}
    salvar_overlays()
    return jsonify({"status": "ok"})
