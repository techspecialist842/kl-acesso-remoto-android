from flask import Blueprint, request, jsonify

bp = Blueprint("dispositivos", __name__)

dispositivos = {}


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


    dispositivos[dispositivo_id] = dados


    return jsonify({
        "status": "ok"
    })



@bp.get("/dispositivos")
def listar():

    return jsonify(
        list(dispositivos.values())
    )
