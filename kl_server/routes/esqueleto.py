import json
import os

from flask import Blueprint, request, jsonify

from routes.dispositivos import atualizar_dispositivo_heartbeat, dispositivo_bloqueado


bp = Blueprint("esqueleto", __name__)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CAMINHO_ESQUELETOS = os.path.join(BASE_DIR, "data", "esqueletos.json")



def carregar_esqueletos():

    if not os.path.exists(CAMINHO_ESQUELETOS):
        return {}


    try:

        with open(
            CAMINHO_ESQUELETOS,
            "r",
            encoding="utf-8"
        ) as arquivo:

            dados = json.load(arquivo)

            if isinstance(dados, dict):
                return dados


    except Exception:

        pass


    return {}




def salvar_esqueletos():

    os.makedirs(
        os.path.dirname(CAMINHO_ESQUELETOS),
        exist_ok=True
    )


    with open(
        CAMINHO_ESQUELETOS,
        "w",
        encoding="utf-8"
    ) as arquivo:

        json.dump(
            esqueletos,
            arquivo,
            ensure_ascii=False,
            indent=4
        )


def salvar_esqueletos_dict(dados):
    os.makedirs(os.path.dirname(CAMINHO_ESQUELETOS), exist_ok=True)
    with open(CAMINHO_ESQUELETOS, "w", encoding="utf-8") as arquivo:
        json.dump(dados, arquivo, ensure_ascii=False, indent=4)
    global esqueletos
    esqueletos = dados




esqueletos = carregar_esqueletos()




@bp.post("/receber_esqueleto")
def receber():


    dados = request.get_json(silent=True)


    if not dados:

        return jsonify({
            "erro": "dados inválidos"
        }), 400



    dispositivo_id = dados.get("id")


    if not dispositivo_id:

        dispositivo_id = request.remote_addr



    if dispositivo_bloqueado(dispositivo_id):
        return jsonify({"status": "ignorado"})

    esqueletos_dados = carregar_esqueletos()
    esqueletos_dados[dispositivo_id] = dados
    salvar_esqueletos_dict(esqueletos_dados)
    atualizar_dispositivo_heartbeat(dispositivo_id, dados)


    return jsonify({
        "status": "ok"
    })





@bp.get("/esqueleto")
def obter():

    dispositivo_id = request.args.get("ip")


    dados = esqueletos.get(
        dispositivo_id,
        {}
    )


    return jsonify(dados)
