from flask import Flask

from routes.dispositivos import bp as dispositivos_bp
from routes.esqueleto import bp as esqueleto_bp
from routes.comandos import bp as comandos_bp
from routes.painel import bp as painel_bp
from routes.overlay import bp as overlay_bp
from routes.apk import bp as apk_bp
from routes.branding import bp as branding_bp
from routes.auth import bp as auth_bp
from routes.usuarios import bp as usuarios_bp
from services.auth import configurar_app, sessao_atual


app = Flask(__name__)
configurar_app(app)


@app.context_processor
def injetar_contexto():
    from services.branding import obter_branding
    usuario = sessao_atual()
    if usuario and not usuario.get("expirado"):
        usuario = dict(usuario)
        if usuario.get("is_admin"):
            usuario["sufixo_login"] = " (admin)"
        else:
            usuario["sufixo_login"] = f" — expira {usuario.get('expira_em_fmt', '-')}"
    else:
        usuario = None
    return {
        "branding": obter_branding(),
        "usuario_logado": usuario,
    }


app.register_blueprint(dispositivos_bp)
app.register_blueprint(esqueleto_bp)
app.register_blueprint(comandos_bp)
app.register_blueprint(painel_bp)
app.register_blueprint(overlay_bp)
app.register_blueprint(apk_bp)
app.register_blueprint(branding_bp)
app.register_blueprint(auth_bp)
app.register_blueprint(usuarios_bp)


@app.get("/")
def inicio():
    return "KL SERVER ONLINE"


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=9001)
