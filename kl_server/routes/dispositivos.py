import json
import os
import time

from flask import Blueprint, request, jsonify


bp = Blueprint("dispositivos", __name__)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CAMINHO_DADOS = os.path.join(BASE_DIR, "data", "dispositivos.json")
CAMINHO_REMOVIDOS = os.path.join(BASE_DIR, "data", "dispositivos_removidos.json")



def carregar_dispositivos():

    if not os.path.exists(CAMINHO_DADOS):
        return {}


    try:

        with open(
            CAMINHO_DADOS,
            "r",
            encoding="utf-8"
        ) as arquivo:

            dados = json.load(arquivo)

            if isinstance(dados, dict):
                return dados


    except Exception:

        return {}


    return {}




def salvar_dispositivos():

    os.makedirs(
        os.path.dirname(CAMINHO_DADOS),
        exist_ok=True
    )


    with open(
        CAMINHO_DADOS,
        "w",
        encoding="utf-8"
    ) as arquivo:

        json.dump(
            dispositivos,
            arquivo,
            ensure_ascii=False,
            indent=4
        )




def carregar_removidos():
    if not os.path.exists(CAMINHO_REMOVIDOS):
        return set()
    try:
        with open(CAMINHO_REMOVIDOS, "r", encoding="utf-8") as arquivo:
            dados = json.load(arquivo)
            if isinstance(dados, list):
                return set(dados)
    except Exception:
        pass
    return set()


def salvar_removidos(removidos):
    os.makedirs(os.path.dirname(CAMINHO_REMOVIDOS), exist_ok=True)
    with open(CAMINHO_REMOVIDOS, "w", encoding="utf-8") as arquivo:
        json.dump(sorted(removidos), arquivo, ensure_ascii=False, indent=4)


dispositivos = carregar_dispositivos()
removidos = carregar_removidos()


def dispositivo_bloqueado(dispositivo_id):
    return dispositivo_id in removidos


def atualizar_dispositivo_heartbeat(dispositivo_id, dados_novos):
    if dispositivo_bloqueado(dispositivo_id):
        return False

    atual = dispositivos.get(dispositivo_id, {})
    dispositivos[dispositivo_id] = {
        **atual,
        "id": dispositivo_id,
        "modelo": dados_novos.get("modelo", atual.get("modelo", "Android")),
        "fabricante": dados_novos.get("fabricante", atual.get("fabricante", "")),
        "android": dados_novos.get("android", atual.get("android", "")),
        "largura": dados_novos.get("largura", atual.get("largura", 0)),
        "altura": dados_novos.get("altura", atual.get("altura", 0)),
        "status": "online",
        "ultimo_contato": int(time.time()),
    }
    salvar_dispositivos()
    return True


def renomear_dispositivo(dispositivo_id, nome):
    if dispositivo_id not in dispositivos:
        raise ValueError("dispositivo nao encontrado")
    dispositivos[dispositivo_id]["nome"] = (nome or "").strip()
    salvar_dispositivos()
    return dispositivos[dispositivo_id]


def remover_dispositivo(dispositivo_id):
    if dispositivo_id not in dispositivos:
        raise ValueError("dispositivo nao encontrado")

    from routes.esqueleto import esqueletos, salvar_esqueletos
    from routes.overlay import carregar_overlays, salvar_overlays

    dispositivos.pop(dispositivo_id, None)
    salvar_dispositivos()

    if dispositivo_id in esqueletos:
        esqueletos.pop(dispositivo_id, None)
        salvar_esqueletos()

    overlays = carregar_overlays()
    if dispositivo_id in overlays:
        overlays.pop(dispositivo_id, None)
        salvar_overlays(overlays)

    removidos.add(dispositivo_id)
    salvar_removidos(removidos)


@bp.post("/registrar_dispositivo")
def registrar():


    dados = request.get_json(silent=True)


    if not dados:

        return jsonify({
            "erro": "dados inválidos"
        }), 400



    dispositivo_id = dados.get("id")


    if not dispositivo_id:

        return jsonify({
            "erro": "id obrigatório"
        }), 400



    if dispositivo_bloqueado(dispositivo_id):
        return jsonify({"status": "ignorado"})

    atual = dispositivos.get(dispositivo_id, {})
    dispositivos[dispositivo_id] = {
        **atual,
        **dados,
        "id": dispositivo_id,
        "status": "online",
        "ultimo_contato": int(time.time()),
    }

    salvar_dispositivos()



    return jsonify({
        "status": "ok"
    })





@bp.get("/dispositivos")
def listar():


    return jsonify(
        list(dispositivos.values())
    )
