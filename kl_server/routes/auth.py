from flask import Blueprint, redirect, render_template, request, session, url_for

from services.auth import sessao_atual
from services.usuarios import autenticar

bp = Blueprint("auth", __name__)


@bp.get("/login")
def login():
    usuario = sessao_atual()
    if usuario and not usuario.get("expirado"):
        return redirect(url_for("painel.index"))
    return render_template("login.html", erro=None)


@bp.post("/login")
def login_post():
    usuario = request.form.get("usuario", "").strip()
    senha = request.form.get("senha", "")
    destino = request.form.get("next") or url_for("painel.index")

    dados, erro = autenticar(usuario, senha)
    if erro:
        return render_template("login.html", erro=erro, usuario=usuario)

    session.clear()
    session.permanent = True
    session["user_id"] = dados["id"]
    session["usuario"] = dados["usuario"]
    session["is_admin"] = dados["is_admin"]
    return redirect(destino)


@bp.get("/logout")
def logout():
    session.clear()
    return redirect(url_for("auth.login"))
