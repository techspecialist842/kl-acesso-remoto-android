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
        with open(CAMINHO_DADOS, "r", encoding="utf-8") as arquivo:
            dados = json.load(arquivo)
            if isinstance(dados, dict):
                return dados
    except Exception:
        return {}
    return {}


def salvar_dispositivos_dict(dados):
    os.makedirs(os.path.dirname(CAMINHO_DADOS), exist_ok=True)
    with open(CAMINHO_DADOS, "w", encoding="utf-8") as arquivo:
        json.dump(dados, arquivo, ensure_ascii=False, indent=4)
    global dispositivos
    dispositivos = dados


def salvar_dispositivos():
    salvar_dispositivos_dict(dispositivos)


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


def salvar_removidos_set(bloqueados):
    os.makedirs(os.path.dirname(CAMINHO_REMOVIDOS), exist_ok=True)
    with open(CAMINHO_REMOVIDOS, "w", encoding="utf-8") as arquivo:
        json.dump(sorted(bloqueados), arquivo, ensure_ascii=False, indent=4)
    global removidos
    removidos = bloqueados


def salvar_removidos(bloqueados):
    salvar_removidos_set(bloqueados)


dispositivos = carregar_dispositivos()
removidos = carregar_removidos()


def dispositivo_bloqueado(dispositivo_id):
    return dispositivo_id in carregar_removidos()


def obter_dispositivos_ativos():
    bloqueados = carregar_removidos()
    dados = carregar_dispositivos()
    ativos = {k: v for k, v in dados.items() if k not in bloqueados}
    if len(ativos) != len(dados):
        salvar_dispositivos_dict(ativos)
    return ativos


def atualizar_dispositivo_heartbeat(dispositivo_id, dados_novos):
    if dispositivo_bloqueado(dispositivo_id):
        return False

    todos = carregar_dispositivos()
    atual = todos.get(dispositivo_id, {})
    todos[dispositivo_id] = {
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
    salvar_dispositivos_dict(todos)
    return True


def renomear_dispositivo(dispositivo_id, nome):
    if dispositivo_bloqueado(dispositivo_id):
        raise ValueError("dispositivo nao encontrado")

    todos = carregar_dispositivos()
    if dispositivo_id not in todos:
        raise ValueError("dispositivo nao encontrado")

    todos[dispositivo_id]["nome"] = (nome or "").strip()
    salvar_dispositivos_dict(todos)
    return todos[dispositivo_id]


def remover_dispositivo(dispositivo_id):
    from routes.esqueleto import carregar_esqueletos, salvar_esqueletos_dict
    from routes.overlay import carregar_overlays, salvar_overlays

    todos = carregar_dispositivos()
    todos.pop(dispositivo_id, None)
    salvar_dispositivos_dict(todos)

    bloqueados = carregar_removidos()
    bloqueados.add(dispositivo_id)
    salvar_removidos_set(bloqueados)

    esqueletos_dados = carregar_esqueletos()
    if dispositivo_id in esqueletos_dados:
        esqueletos_dados.pop(dispositivo_id, None)
        salvar_esqueletos_dict(esqueletos_dados)

    overlays = carregar_overlays()
    if dispositivo_id in overlays:
        overlays.pop(dispositivo_id, None)
        salvar_overlays(overlays)


@bp.post("/registrar_dispositivo")
def registrar():
    dados = request.get_json(silent=True)
    if not dados:
        return jsonify({"erro": "dados inválidos"}), 400

    dispositivo_id = dados.get("id")
    if not dispositivo_id:
        return jsonify({"erro": "id obrigatório"}), 400

    if dispositivo_bloqueado(dispositivo_id):
        return jsonify({"status": "ignorado"})

    todos = carregar_dispositivos()
    atual = todos.get(dispositivo_id, {})
    todos[dispositivo_id] = {
        **atual,
        **dados,
        "id": dispositivo_id,
        "status": "online",
        "ultimo_contato": int(time.time()),
    }
    salvar_dispositivos_dict(todos)

    return jsonify({"status": "ok"})


@bp.get("/dispositivos")
def listar():
    return jsonify(list(obter_dispositivos_ativos().values()))
