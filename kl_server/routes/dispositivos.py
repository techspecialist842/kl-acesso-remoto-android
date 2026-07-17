import json
import os
import time

from flask import Blueprint, request, jsonify


bp = Blueprint("dispositivos", __name__)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CAMINHO_DADOS = os.path.join(BASE_DIR, "data", "dispositivos.json")



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




dispositivos = carregar_dispositivos()




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



    dispositivos[dispositivo_id] = {
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
