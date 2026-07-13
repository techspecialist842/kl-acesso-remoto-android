import json
import os
import time

from flask import Blueprint, request, jsonify

from routes.dispositivos import dispositivos, salvar_dispositivos


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



    esqueletos[dispositivo_id] = dados


    salvar_esqueletos()



    dispositivos[dispositivo_id] = {

        "id": dispositivo_id,

        "modelo": dados.get(
            "modelo",
            "Android"
        ),

        "fabricante": dados.get(
            "fabricante",
            ""
        ),

        "android": dados.get(
            "android",
            ""
        ),

        "largura": dados.get(
            "largura",
            0
        ),

        "altura": dados.get(
            "altura",
            0
        ),

        "status": "online",

        "ultimo_contato": int(
            time.time()
        )

    }


    salvar_dispositivos()


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
