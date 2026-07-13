from flask import Blueprint, request, jsonify

bp = Blueprint("comandos", __name__)

fila_comandos = {}


@bp.post("/enviar_comando")
def enviar():

    dados = request.get_json(silent=True)

    if not dados:
        return jsonify({"erro": "dados inválidos"}), 400

    dispositivo = dados.get("id")
    comando = dados.get("comando")

    if not dispositivo or not comando:
        return jsonify({"erro": "id e comando obrigatórios"}), 400

    fila_comandos.setdefault(dispositivo, []).append(comando)

    return jsonify({"status": "ok"})


@bp.get("/obter_comando")
def obter():

    dispositivo = request.args.get("id")

    if not dispositivo:
        return "nenhum"

    fila = fila_comandos.get(dispositivo, [])

    if not fila:
        return "nenhum"

    return fila.pop(0)
