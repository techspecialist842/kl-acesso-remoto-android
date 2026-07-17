from flask import Blueprint, jsonify, render_template, request

from services.auth import admin_obrigatorio
from services.usuarios import (
    PLANOS_VALIDOS,
    atualizar_usuario,
    criar_usuario,
    excluir_usuario,
    listar_usuarios,
)

bp = Blueprint("usuarios", __name__)


@bp.get("/painel/usuarios")
@admin_obrigatorio
def pagina_usuarios():
    return render_template(
        "usuarios.html",
        usuarios=listar_usuarios(),
        planos=sorted(PLANOS_VALIDOS),
    )


@bp.get("/api/usuarios")
@admin_obrigatorio
def api_listar():
    return jsonify(listar_usuarios())


@bp.post("/api/usuarios")
@admin_obrigatorio
def api_criar():
    dados = request.get_json(silent=True) or request.form.to_dict()
    try:
        criar_usuario(
            dados.get("usuario", ""),
            dados.get("senha", ""),
            dados.get("nome", ""),
            dados.get("plano_dias", 7),
        )
        return jsonify({"status": "ok"})
    except ValueError as exc:
        return jsonify({"erro": str(exc)}), 400


@bp.put("/api/usuarios/<user_id>")
@bp.patch("/api/usuarios/<user_id>")
@admin_obrigatorio
def api_atualizar(user_id):
    dados = request.get_json(silent=True) or {}
    try:
        usuario = atualizar_usuario(
            user_id,
            nome=dados.get("nome"),
            senha=dados.get("senha") or None,
            plano_dias=dados.get("plano_dias"),
            ativo=dados.get("ativo"),
            renovar=bool(dados.get("renovar")),
        )
        return jsonify({"status": "ok", "usuario": usuario})
    except ValueError as exc:
        return jsonify({"erro": str(exc)}), 400


@bp.delete("/api/usuarios/<user_id>")
@admin_obrigatorio
def api_excluir(user_id):
    try:
        excluir_usuario(user_id)
        return jsonify({"status": "ok"})
    except ValueError as exc:
        return jsonify({"erro": str(exc)}), 400
