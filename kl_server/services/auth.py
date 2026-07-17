import os
from functools import wraps

from flask import jsonify, redirect, request, session, url_for

from services.usuarios import buscar_por_id, garantir_admin_padrao, usuario_pode_acessar

SECRET_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "data",
    "session_secret.txt",
)


def obter_secret_key():
    if os.environ.get("KL_SECRET_KEY"):
        return os.environ["KL_SECRET_KEY"]
    os.makedirs(os.path.dirname(SECRET_PATH), exist_ok=True)
    if os.path.exists(SECRET_PATH):
        with open(SECRET_PATH, encoding="utf-8") as arquivo:
            chave = arquivo.read().strip()
            if chave:
                return chave
    chave = os.urandom(32).hex()
    with open(SECRET_PATH, "w", encoding="utf-8") as arquivo:
        arquivo.write(chave)
    return chave


def configurar_app(app):
    garantir_admin_padrao()
    app.secret_key = obter_secret_key()
    app.config["SESSION_COOKIE_HTTPONLY"] = True
    app.config["SESSION_COOKIE_SAMESITE"] = "Lax"
    app.config["PERMANENT_SESSION_LIFETIME"] = 86400 * 7


def sessao_atual():
    user_id = session.get("user_id")
    if not user_id:
        return None
    dados = buscar_por_id(user_id)
    if not dados:
        session.clear()
        return None
    ok, motivo = usuario_pode_acessar(dados)
    if not ok:
        session.clear()
        return {"expirado": True, "motivo": motivo}
    return dados


def login_obrigatorio(view):
    @wraps(view)
    def wrapper(*args, **kwargs):
        usuario = sessao_atual()
        if not usuario or usuario.get("expirado"):
            if request.path.startswith("/api/"):
                return jsonify({"erro": "Acesso negado. Faca login."}), 401
            destino = request.url
            return redirect(url_for("auth.login", next=destino))
        return view(*args, **kwargs)

    return wrapper


def admin_obrigatorio(view):
    @wraps(view)
    def wrapper(*args, **kwargs):
        usuario = sessao_atual()
        if not usuario or usuario.get("expirado"):
            if request.path.startswith("/api/"):
                return jsonify({"erro": "Acesso negado"}), 401
            return redirect(url_for("auth.login"))
        if not usuario.get("is_admin"):
            if request.path.startswith("/api/"):
                return jsonify({"erro": "Apenas administrador"}), 403
            return redirect(url_for("painel.index"))
        return view(*args, **kwargs)

    return wrapper
